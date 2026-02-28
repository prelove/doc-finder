package org.abitware.docfinder.index;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class IndexSettingsTest {

    @Test
    void shouldHaveDefaultNrtCacheMaxMB() {
        IndexSettings s = new IndexSettings();
        assertEquals(32, s.nrtCacheMaxMB,
                "Default nrtCacheMaxMB should be 32 to enable NRTCachingDirectory by default");
    }

    @Test
    void shouldAllowDisablingNrtCache() {
        IndexSettings s = new IndexSettings();
        s.nrtCacheMaxMB = 0;
        assertEquals(0, s.nrtCacheMaxMB);
    }

    @Test
    void shouldHaveDefaultMaxFileMB() {
        IndexSettings s = new IndexSettings();
        assertEquals(50L, s.maxFileMB);
    }

    @Test
    void shouldHaveDefaultParseTimeoutSec() {
        IndexSettings s = new IndexSettings();
        assertEquals(15, s.parseTimeoutSec);
    }

    @Test
    void shouldHaveDefaultPreviewTimeoutSec() {
        IndexSettings s = new IndexSettings();
        assertEquals(5, s.previewTimeoutSec);
    }
}
