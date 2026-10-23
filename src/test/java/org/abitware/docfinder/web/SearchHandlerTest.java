package org.abitware.docfinder.web;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import org.abitware.docfinder.search.SearchException;
import org.abitware.docfinder.search.SearchRequest;
import org.abitware.docfinder.search.SearchResult;
import org.abitware.docfinder.search.SearchService;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A6/DS-08~12: HTTP contract for directory-scoped search — query params,
 * typed error mapping ({error,code} + status), parentDirectory field,
 * JSON escaping, and fixed-message guarantees (no raw exception text).
 */
class SearchHandlerTest {

    /** Minimal HttpExchange stub that records status and body. */
    private static final class FakeExchange extends HttpExchange {
        private final URI uri;
        private final String method;
        private final Headers responseHeaders = new Headers();
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();
        int status = -1;

        FakeExchange(String rawQuery) {
            this.uri = URI.create("/api/search" + (rawQuery == null ? "" : "?" + rawQuery));
            this.method = "GET";
        }

        @Override public Headers getRequestHeaders() { return new Headers(); }
        @Override public Headers getResponseHeaders() { return responseHeaders; }
        @Override public URI getRequestURI() { return uri; }
        @Override public String getRequestMethod() { return method; }
        @Override public HttpContext getHttpContext() { return null; }
        @Override public void close() { }
        @Override public InputStream getRequestBody() { return new ByteArrayInputStream(new byte[0]); }
        @Override public OutputStream getResponseBody() { return body; }
        @Override public void sendResponseHeaders(int code, long len) { this.status = code; }
        @Override public InetSocketAddress getRemoteAddress() { return null; }
        @Override public InetSocketAddress getLocalAddress() { return null; }
        @Override public String getProtocol() { return "HTTP/1.1"; }
        @Override public Object getAttribute(String name) { return null; }
        @Override public void setAttribute(String name, Object value) { }
        @Override public void setStreams(InputStream i, OutputStream o) { }
        @Override public HttpPrincipal getPrincipal() { return null; }
        @Override public int getResponseCode() { return status; }

        String bodyText() {
            return new String(body.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static final class StubService implements SearchService {
        SearchRequest lastRequest;
        List<SearchResult> canned = Collections.emptyList();
        RuntimeException failure;

        @Override
        public List<SearchResult> search(SearchRequest request) {
            lastRequest = request;
            if (failure != null) throw failure;
            return canned;
        }

        @Override public void close() { }
    }

    private static FakeExchange get(SearchService svc, String rawQuery) throws IOException {
        SearchHandler h = new SearchHandler(() -> svc);
        FakeExchange ex = new FakeExchange(rawQuery);
        h.handle(ex);
        return ex;
    }

    // ── Empty query / null service ordering ─────────────────────────────────

    @Test
    void shouldReturnEmptySuccessForBlankQueryEvenWhenServiceIsNull() throws Exception {
        FakeExchange ex = get(null, "q=+&directory=E%3A%5Cbad");
        assertEquals(200, ex.status);
        assertEquals("{\"results\":[],\"count\":0}", ex.bodyText());
    }

    @Test
    void shouldReturn503IndexUnavailableWhenServiceNull() throws Exception {
        FakeExchange ex = get(null, "q=alpha");
        assertEquals(503, ex.status);
        assertTrue(ex.bodyText().contains("\"code\":\"INDEX_UNAVAILABLE\""), ex.bodyText());
        assertFalse(ex.bodyText().contains("initializing"), "legacy message replaced");
    }

    // ── directory parameter ─────────────────────────────────────────────────

    @Test
    void shouldPassDirectoryParamToRequest() throws Exception {
        StubService svc = new StubService();
        FakeExchange ex = get(svc, "q=alpha&directory=E%3A%5CRoot%5CSub");
        assertEquals(200, ex.status);
        assertEquals("E:\\Root\\Sub", svc.lastRequest.directoryPath);
        assertEquals("alpha", svc.lastRequest.query);
    }

    @Test
    void shouldLeaveDirectoryNullWhenParamAbsent() throws Exception {
        StubService svc = new StubService();
        get(svc, "q=alpha");
        assertNull(svc.lastRequest.directoryPath);
    }

    // ── parentDirectory field in results ────────────────────────────────────

    @Test
    void shouldIncludeParentDirectoryPerResult() throws Exception {
        StubService svc = new StubService();
        svc.canned = Arrays.asList(
                new SearchResult("a.txt", "E:\\Root\\a.txt", 1f, 0L, 0L, "name", 5L, false),
                new SearchResult("Sub", "E:\\Root\\Sub", 1f, 0L, 0L, "folder", 0L, true));
        FakeExchange ex = get(svc, "q=a");
        assertEquals(200, ex.status);
        String body = ex.bodyText();
        assertTrue(body.contains("\"parentDirectory\":\"E:\\\\Root\""), body);
        // two results => two occurrences
        assertEquals(2, body.split("parentDirectory").length - 1);
    }

    @Test
    void shouldEmitNullParentDirectoryForRootOrUnparseable() throws Exception {
        StubService svc = new StubService();
        svc.canned = Collections.singletonList(
                new SearchResult("x", "no-parent-here", 1f, 0L, 0L, "name", 0L, false));
        FakeExchange ex = get(svc, "q=x");
        assertEquals(200, ex.status);
        assertTrue(ex.bodyText().contains("\"parentDirectory\":null"), ex.bodyText());
    }

    // ── Typed error mapping ─────────────────────────────────────────────────

    @Test
    void shouldMapAllEightCodesToStatusAndFixedMessage() throws Exception {
        Object[][] table = {
                {SearchException.Code.INVALID_DIRECTORY, 400},
                {SearchException.Code.DIRECTORY_NOT_INDEXED, 422},
                {SearchException.Code.SOURCE_CONFIG_UNAVAILABLE, 503},
                {SearchException.Code.INDEX_REBUILD_REQUIRED, 409},
                {SearchException.Code.SCOPE_CHECK_FAILED, 503},
                {SearchException.Code.SEARCH_FAILED, 500},
                {SearchException.Code.INVALID_QUERY, 400},
                {SearchException.Code.INDEX_UNAVAILABLE, 503},
        };
        for (Object[] row : table) {
            SearchException.Code code = (SearchException.Code) row[0];
            StubService svc = new StubService();
            svc.failure = new SearchException(code, "RAW-INTERNAL-DETAIL-" + code);
            FakeExchange ex = get(svc, "q=alpha");
            assertEquals((Integer) row[1], ex.status, code.name());
            String body = ex.bodyText();
            assertTrue(body.contains("\"code\":\"" + code.name() + "\""), code + " body=" + body);
            assertFalse(body.contains("RAW-INTERNAL-DETAIL"), code + " leaks message: " + body);
            assertTrue(body.contains("\"error\":\""), "missing error field for " + code);
        }
    }

    @Test
    void shouldMapInvalidQueryConsistentlyWithAndWithoutDirectory() throws Exception {
        StubService svc = new StubService();
        svc.failure = new SearchException(SearchException.Code.INVALID_QUERY, "bad syntax");
        FakeExchange unscoped = get(svc, "q=bad+(syntax");
        FakeExchange scoped = get(svc, "q=bad+(syntax&directory=E%3A%5CRoot");
        assertEquals(400, unscoped.status);
        assertEquals(400, scoped.status);
        assertTrue(unscoped.bodyText().contains("\"code\":\"INVALID_QUERY\""));
        assertTrue(scoped.bodyText().contains("\"code\":\"INVALID_QUERY\""));
    }

    @Test
    void shouldMapUnknownExceptionToSearchFailedWithoutLeaking() throws Exception {
        StubService svc = new StubService();
        svc.failure = new IllegalStateException("secret-stack-cause-12345");
        FakeExchange ex = get(svc, "q=alpha");
        assertEquals(500, ex.status);
        String body = ex.bodyText();
        assertTrue(body.contains("\"code\":\"SEARCH_FAILED\""), body);
        assertFalse(body.contains("secret-stack-cause-12345"), body);
    }

    // ── JSON safety ─────────────────────────────────────────────────────────

    @Test
    void shouldEscapeControlCharactersAndBackslashesInJson() throws Exception {
        StubService svc = new StubService();
        svc.canned = Collections.singletonList(new SearchResult(
                "n\tame\"x", "E:\\p\u0001ath", 1f, 0L, 0L, "name", 0L, false));
        FakeExchange ex = get(svc, "q=x");
        assertEquals(200, ex.status);
        String body = ex.bodyText();
        assertFalse(body.contains("\t"), "raw tab must be escaped");
        assertFalse(body.contains("\u0001"), "raw control char must be escaped");
        assertTrue(body.contains("\\t"), body);
        assertTrue(body.contains("\\u0001"), body);
        // Result must remain valid JSON: every control char <0x20 escaped.
        for (char c : body.toCharArray()) {
            assertFalse(c < 0x20, "unescaped control char in body");
        }
    }

    @Test
    void shouldPreserveNonEnglishTextInJson() throws Exception {
        StubService svc = new StubService();
        svc.canned = Collections.singletonList(new SearchResult(
                "报告文件.txt", "E:\\中文目录\\报告文件.txt", 1f, 0L, 0L, "name", 0L, false));
        FakeExchange ex = get(svc, "q=%E6%8A%A5%E5%91%8A");
        assertEquals(200, ex.status);
        assertTrue(ex.bodyText().contains("报告文件.txt"), ex.bodyText());
    }

    @Test
    void shouldSerializeScoresIndependentlyOfFormatLocale() throws Exception {
        Locale previous = Locale.getDefault(Locale.Category.FORMAT);
        try {
            Locale.setDefault(Locale.Category.FORMAT, Locale.GERMANY);
            StubService service = new StubService();
            service.canned = Collections.singletonList(new SearchResult(
                    "report.txt", "E:\\Root\\report.txt", 1.2345f, 0L, 0L, "content", 1L, false));
            for (String rawQuery : Arrays.asList("q=report", "q=report&directory=E%3A%5CRoot")) {
                FakeExchange exchange = get(service, rawQuery);
                assertEquals(200, exchange.status);
                assertTrue(exchange.bodyText().contains("\"score\":1.2345,"), exchange.bodyText());
            }
        } finally {
            Locale.setDefault(Locale.Category.FORMAT, previous);
        }
    }
}
