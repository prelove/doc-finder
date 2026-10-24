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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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

    private static Path hostAbs(String name) {
        // A valid absolute path on any host (QA24-08 portability).
        return Paths.get(System.getProperty("java.io.tmpdir")).toAbsolutePath().resolve(name);
    }

    @Test
    void shouldReadLegacyAndNewFormats() throws Exception {
        Path f = writeSources(
                "# comment",
                "",
                hostAbs("Docs").toString(),
                hostAbs("Net").toString() + "|1",
                hostAbs("Local").toString() + "|0");
        List<Path> roots = SourceManager.readSearchRoots(f);
        assertEquals(3, roots.size());
        assertEquals(hostAbs("Docs"), roots.get(0));
        assertEquals(hostAbs("Net"), roots.get(1));
        assertEquals(hostAbs("Local"), roots.get(2));
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
                hostAbs("Valid").toString() + "|1",
                hostAbs("Too").toString() + "|many|cols",
                hostAbs("Bad").toString() + "|9");
        List<Path> roots = SourceManager.readSearchRoots(f);
        assertEquals(1, roots.size());
        assertEquals(hostAbs("Valid"), roots.get(0));
    }

    @Test
    void shouldFailWhenAllDataLinesInvalid() throws Exception {
        Path f = writeSources("relative/dir", hostAbs("Bad").toString() + "|9");
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
    void shouldFailOnAccessDeniedNotReportEmpty() throws Exception {
        // QA24-05: deny read on the FILE itself — Files.exists() then reports
        // false (existence undecidable), yet the file is real and unreadable.
        // Only a definite NoSuchFile may mean "no sources"; this must throw.
        Path f = writeSources("E:\\Docs");
        java.nio.file.attribute.AclFileAttributeView view =
                Files.getFileAttributeView(f, java.nio.file.attribute.AclFileAttributeView.class);
        assumeTrue(view != null, "ACL attribute view unsupported on this filesystem");
        java.nio.file.attribute.UserPrincipal me;
        try {
            me = java.nio.file.FileSystems.getDefault().getUserPrincipalLookupService()
                    .lookupPrincipalByName(System.getProperty("user.name"));
        } catch (IOException e) {
            assumeTrue(false, "cannot resolve current principal: " + e);
            return;
        }
        java.util.List<java.nio.file.attribute.AclEntry> original =
                new java.util.ArrayList<>(view.getAcl());
        java.util.List<java.nio.file.attribute.AclEntry> denied =
                new java.util.ArrayList<>(original);
        denied.add(0, java.nio.file.attribute.AclEntry.newBuilder()
                .setType(java.nio.file.attribute.AclEntryType.DENY)
                .setPrincipal(me)
                .setPermissions(java.nio.file.attribute.AclEntryPermission.READ_DATA,
                        java.nio.file.attribute.AclEntryPermission.READ_ATTRIBUTES)
                .build());
        view.setAcl(denied);
        try {
            assumeTrue(!Files.exists(f),
                    "environment could not reproduce undecidable existence — ACL not enforced");
            assertThrows(IOException.class, () -> SourceManager.readSearchRoots(f),
                    "access-denied sources file must surface an error, not report empty");
        } finally {
            view.setAcl(original); // restore so @TempDir cleanup succeeds
        }
    }

    @Test
    void shouldReturnUnmodifiableSnapshot() throws Exception {
        Path f = writeSources("E:\\Docs");
        List<Path> roots = SourceManager.readSearchRoots(f);
        assertThrows(UnsupportedOperationException.class,
                () -> roots.add(Paths.get("X:\\Other")));
    }
}
