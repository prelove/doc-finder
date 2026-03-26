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

    private String readResource(String path) throws IOException {
        InputStream in = getClass().getResourceAsStream(path);
        assertNotNull(in, "Missing resource: " + path);
        try (InputStream stream = in) {
            byte[] bytes = StaticHandler.readAll(stream);
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }
}

