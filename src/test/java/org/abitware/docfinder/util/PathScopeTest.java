package org.abitware.docfinder.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Path normalization for directory scope: Windows hosts fold case and map
 * separators to '/', POSIX keeps case. Indexed-path and query-directory
 * normalization must agree for every supported absolute path.
 */
@Execution(ExecutionMode.SAME_THREAD)
class PathScopeTest {

    private static void windows() {
        assumeTrue(Utils.isWindows(), "Windows-only case");
    }

    @Test
    void shouldReturnNullForBlankDirectory() {
        assertNull(PathScope.normalizeDirectory(null));
        assertNull(PathScope.normalizeDirectory(""));
        assertNull(PathScope.normalizeDirectory("   "));
    }

    @Test
    void shouldNormalizeEquivalentWindowsForms() {
        windows();
        String expected = "e:/docs/projecta";
        assertEquals(expected, PathScope.normalizeDirectory("E:\\Docs\\ProjectA"));
        assertEquals(expected, PathScope.normalizeDirectory("e:/docs/projecta/"));
        assertEquals(expected, PathScope.normalizeDirectory("E:\\Docs\\Other\\..\\ProjectA"));
        assertEquals(expected, PathScope.normalizeDirectory("  E:\\Docs\\ProjectA  "));
        // Japanese full-width Yen / backslash input compatibility
        assertEquals(expected, PathScope.normalizeDirectory("E:¥Docs¥ProjectA"));
    }

    @Test
    void shouldNormalizeDriveRootAndUnc() {
        windows();
        assertEquals("e:/", PathScope.normalizeDirectory("E:\\"));
        assertEquals("e:/", PathScope.normalizeDirectory("E:/"));
        assertEquals("//server/share", PathScope.normalizeDirectory("\\\\server\\share\\"));
        assertEquals("//server/share/dir",
                PathScope.normalizeDirectory("\\\\server\\share\\dir"));
    }

    @Test
    void shouldRejectInvalidWindowsDirectory() {
        windows();
        assertThrows(IllegalArgumentException.class,
                () -> PathScope.normalizeDirectory("Docs"));
        assertThrows(IllegalArgumentException.class,
                () -> PathScope.normalizeDirectory("E:Docs"));
        assertThrows(IllegalArgumentException.class,
                () -> PathScope.normalizeDirectory("E:\\bad\0x"));
        // Extended / device namespace not supported in this phase
        assertThrows(IllegalArgumentException.class,
                () -> PathScope.normalizeDirectory("\\\\?\\E:\\x"));
        assertThrows(IllegalArgumentException.class,
                () -> PathScope.normalizeDirectory("\\\\.\\pipe\\x"));
    }

    @Test
    void shouldMatchIndexedAndQueryNormalization() {
        windows();
        Path p = Paths.get("E:\\Docs\\ProjectA");
        assertEquals(PathScope.normalizeDirectory("E:\\Docs\\ProjectA"),
                PathScope.normalizeIndexedPath(p));
        Path unc = Paths.get("\\\\server\\share\\Dir");
        assertEquals(PathScope.normalizeDirectory("\\\\server\\share\\Dir"),
                PathScope.normalizeIndexedPath(unc));
    }

    @Test
    void shouldBuildDescendantPrefix() {
        assertEquals("e:/docs/", PathScope.descendantPrefix("e:/docs"));
        assertEquals("e:/", PathScope.descendantPrefix("e:/"));
        assertEquals("//server/share/",
                PathScope.descendantPrefix("//server/share"));
        assertEquals("/", PathScope.descendantPrefix("/"));
        assertNull(PathScope.descendantPrefix(null));
    }

    @Test
    void shouldKeepPosixCaseAndSeparators() {
        assumeTrue(!Utils.isWindows(), "POSIX-only case");
        assertEquals("/data/Docs", PathScope.normalizeDirectory("/data/Docs/"));
        assertNotEquals(PathScope.normalizeDirectory("/data/Docs"),
                PathScope.normalizeDirectory("/data/docs"));
        assertThrows(IllegalArgumentException.class,
                () -> PathScope.normalizeDirectory("data/Docs"));
        Path p = Paths.get("/data/Docs");
        assertEquals("/data/Docs", PathScope.normalizeIndexedPath(p));
    }
}
