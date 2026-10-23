package org.abitware.docfinder.index;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Strict-but-tolerant source loading for directory-scoped search: bad lines are
 * logged and skipped, I/O failure or an all-invalid file is an error, a missing
 * or comment-only file means "no sources". Fixtures use a temporary
 * sources.txt; the real configuration is never read.
 */
@Execution(ExecutionMode.SAME_THREAD)
class SourceManagerSearchRootsTest {

    @TempDir
    Path tmp;

    private Path writeSources(String... lines) throws IOException {
        Path f = tmp.resolve("sources.txt");
        Files.write(f, String.join("\n", lines).getBytes("UTF-8"));
        return f;
    }

    @Test
    void shouldReadLegacyAndNewFormats() throws Exception {
        Path f = writeSources(
                "# comment",
                "",
                "E:\\Docs",
                "F:\\Net|1",
                "G:\\Local|0");
        List<Path> roots = SourceManager.readSearchRoots(f);
        assertEquals(3, roots.size());
        assertEquals(Paths.get("E:\\Docs"), roots.get(0));
        assertEquals(Paths.get("F:\\Net"), roots.get(1));
        assertEquals(Paths.get("G:\\Local"), roots.get(2));
    }

    @Test
    void shouldReturnEmptyForMissingOrCommentOnlyFile() throws Exception {
        assertTrue(SourceManager.readSearchRoots(tmp.resolve("none.txt")).isEmpty());
        Path f = writeSources("# only", "   ", "# comments");
        assertTrue(SourceManager.readSearchRoots(f).isEmpty());
    }

    @Test
    void shouldSkipBadLinesAndKeepValid() throws Exception {
        Path f = writeSources(
                "relative/dir",
                "E:\\Valid|1",
                "D:\\Too|many|cols",
                "C:\\Bad|9");
        List<Path> roots = SourceManager.readSearchRoots(f);
        assertEquals(1, roots.size());
        assertEquals(Paths.get("E:\\Valid"), roots.get(0));
    }

    @Test
    void shouldFailWhenAllDataLinesInvalid() throws Exception {
        Path f = writeSources("relative/dir", "C:\\Bad|9");
        assertThrows(IOException.class, () -> SourceManager.readSearchRoots(f));
    }

    @Test
    void shouldFailOnUnreadableFile() throws Exception {
        // Passing a directory as the sources file must surface IOException,
        // not be swallowed as "no sources".
        Path dir = Files.createDirectories(tmp.resolve("asdir"));
        assertThrows(IOException.class, () -> SourceManager.readSearchRoots(dir));
    }

    @Test
    void shouldReturnUnmodifiableSnapshot() throws Exception {
        Path f = writeSources("E:\\Docs");
        List<Path> roots = SourceManager.readSearchRoots(f);
        assertThrows(UnsupportedOperationException.class,
                () -> roots.add(Paths.get("X:\\Other")));
    }
}
