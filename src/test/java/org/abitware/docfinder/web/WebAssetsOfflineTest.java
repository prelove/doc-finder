package org.abitware.docfinder.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WebAssetsOfflineTest {

    private static final List<String> WEB_PAGES = Arrays.asList(
        "/web/index.html",
        "/web/preview.html",
        "/web/share.html"
    );

    private static final List<String> REQUIRED_LOCAL_ASSETS = Arrays.asList(
        "/web/vendor/js-preview/docx/lib/index.css",
        "/web/vendor/js-preview/docx/lib/index.umd.js",
        "/web/vendor/js-preview/excel/lib/index.css",
        "/web/vendor/js-preview/excel/lib/index.umd.js",
        "/web/vendor/jschardet/dist/jschardet.min.js"
    );

    @Test
    void shouldNotUseExternalHttpResourcesInBundledWebPages() throws IOException {
        for (String page : WEB_PAGES) {
            String html = readResource(page);
            assertFalse(
                html.contains("http://") || html.contains("https://"),
                "External URL found in " + page
            );
        }
    }

    @Test
    void shouldBundleRequiredVendorAssetsForOfflinePreview() {
        for (String resource : REQUIRED_LOCAL_ASSETS) {
            assertNotNull(getClass().getResourceAsStream(resource), "Missing bundled asset: " + resource);
        }
    }

    /**
     * A6/DS-08~12: directory-scope UI surface — server-directory input, Clear,
     * per-row scope quick actions, copy path. Static presence check only;
     * interactive behavior is verified on a real host (M-04/M-05).
     */
    @Test
    void shouldExposeDirectoryScopeControlsInIndexPage() throws IOException {
        String html = readResource("/web/index.html");
        assertTrue(html.contains("id=\"dirInput\""), "missing server directory input");
        assertTrue(html.contains("id=\"clearDirBtn\""), "missing Clear button");
        assertTrue(html.contains("directory="), "search must send directory param");
        assertTrue(html.contains("dirscope-btn"), "missing row-level scope quick action");
        assertTrue(html.contains("copypath-btn"), "missing copy-path action");
        assertTrue(html.contains("parentDirectory"), "must consume server-provided parentDirectory");
        assertFalse(html.contains("/api/sources"), "no source-list API may be added");
        assertFalse(html.contains("/api/browse"), "no server browse API may be added");
        int fn = html.indexOf("function search()");
        String body = html.substring(fn, html.indexOf("function clearCurrentResults()"));
        assertTrue(body.indexOf("++searchToken") < body.indexOf("if (!q)"),
                "token must be invalidated before the empty-query return");
        assertTrue(body.indexOf("data.error") < body.indexOf("addToHistory(q)"),
                "history must only be written after a successful response");
        assertEquals(1, body.split("addToHistory\\(", -1).length - 1, "single history write");
    }

    private String readResource(String path) throws IOException {
        InputStream in = getClass().getResourceAsStream(path);
        assertNotNull(in, "Missing resource: " + path);
        try (InputStream stream = in) {
            byte[] bytes = StaticHandler.readAll(stream);
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }
}

