package org.abitware.docfinder.util;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AppPathsTest {

    @Test
    void shouldReturnAbsolutePath() {
        Path base = AppPaths.getBaseDir();
        assertNotNull(base);
        assertTrue(base.isAbsolute(), "Base dir must be absolute");
    }

    @Test
    void shouldEndWithDocfinderSegment() {
        Path base = AppPaths.getBaseDir();
        assertEquals(".docfinder", base.getFileName().toString());
    }

    @Test
    void shouldBeNormalized() {
        Path base = AppPaths.getBaseDir();
        assertEquals(base, base.normalize(), "Base dir must be normalized");
    }
}
