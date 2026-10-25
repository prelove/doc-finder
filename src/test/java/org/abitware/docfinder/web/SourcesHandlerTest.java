package org.abitware.docfinder.web;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GET /api/sources returns the strict search-time source roots as a JSON array;
 * a loader failure maps to 503 SOURCE_CONFIG_UNAVAILABLE; non-GET is 405.
 */
class SourcesHandlerTest {

    private static final class FakeExchange extends HttpExchange {
        private final Headers responseHeaders = new Headers();
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();
        private final String method;
        int status = -1;

        FakeExchange(String method) {
            this.method = method;
        }

        @Override public Headers getRequestHeaders() { return new Headers(); }
        @Override public Headers getResponseHeaders() { return responseHeaders; }
        @Override public URI getRequestURI() { return URI.create("/api/sources"); }
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

    private static FakeExchange get(SourcesHandler.RootsLoader loader) throws IOException {
        FakeExchange ex = new FakeExchange("GET");
        new SourcesHandler(loader).handle(ex);
        return ex;
    }

    @Test
    void shouldReturnSourcePathsAsJsonArray() throws Exception {
        List<Path> roots = Arrays.asList(Paths.get("D:/docs"), Paths.get("E:/media/sub"));
        FakeExchange ex = get(() -> roots);
        assertEquals(200, ex.status);
        String body = ex.bodyText();
        assertTrue(body.startsWith("{\"sources\":["), body);
        assertTrue(body.contains("\"" + SearchHandler.escapeJson(roots.get(0).toString()) + "\""), body);
        assertTrue(body.contains("\"" + SearchHandler.escapeJson(roots.get(1).toString()) + "\""), body);
        assertTrue(body.endsWith("]}"), body);
    }

    @Test
    void shouldReturnEmptyArrayWhenNoSources() throws Exception {
        FakeExchange ex = get(Collections::emptyList);
        assertEquals(200, ex.status);
        assertEquals("{\"sources\":[]}", ex.bodyText());
    }

    @Test
    void shouldReturn503WhenLoaderFails() throws Exception {
        FakeExchange ex = get(() -> { throw new IOException("sources file unreadable"); });
        assertEquals(503, ex.status);
        assertTrue(ex.bodyText().contains("\"code\":\"SOURCE_CONFIG_UNAVAILABLE\""), ex.bodyText());
    }

    @Test
    void shouldRejectNonGet() throws Exception {
        FakeExchange ex = new FakeExchange("POST");
        new SourcesHandler(Collections::emptyList).handle(ex);
        assertEquals(405, ex.status);
    }
}
