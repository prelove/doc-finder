package org.abitware.docfinder.search;

import org.abitware.docfinder.index.IndexSettings;
import org.abitware.docfinder.index.LuceneIndexer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ghost-hit filtering contract: index documents whose paths were confirmed
 * deleted are dropped from displayed results, the page is refilled from
 * later hits, the index is purged asynchronously, and entries under an
 * unreachable root (offline source) are kept rather than wrongly purged.
 * Real temporary index via LuceneIndexer → LuceneSearchService.
 */
@Execution(ExecutionMode.SAME_THREAD)
class GhostResultFilterTest {

    @TempDir
    Path fixtureRoot;

    private Path source;
    private Path index;
    private IndexSettings settings;
    private LuceneSearchService service;

    @BeforeEach
    void setUp() throws Exception {
        source = fixtureRoot.resolve("source");
        index = fixtureRoot.resolve("index");
        Files.createDirectories(source);
        settings = new IndexSettings();
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.close();
        }
    }

    private static void write(Path p, String content) throws Exception {
        Files.write(p, content.getBytes("UTF-8"));
    }

    private void indexAndOpen() throws Exception {
        try (LuceneIndexer indexer = new LuceneIndexer(index, settings)) {
            indexer.indexFolder(source);
        }
        SourceRootProvider provider = () -> Collections.singletonList(source);
        service = new LuceneSearchService(index, settings, provider);
    }

    private SearchOutcome detailed(String q, int limit) {
        return service.searchDetailed(new SearchRequest(q, limit, null,
                SearchScope.ALL, MatchMode.FUZZY));
    }

    private static List<String> names(List<SearchResult> rs) {
        java.util.List<String> out = new java.util.ArrayList<>(rs.size());
        for (SearchResult r : rs) out.add(r.name);
        return out;
    }

    private static void waitForPurge() throws Exception {
        // ghostCleaner runs the batch on a single daemon thread; give it a
        // short bounded window before asserting index convergence.
        Thread.sleep(700);
    }

    @Test
    void shouldDropDeletedFileAndPurgeIndex() throws Exception {
        write(source.resolve("ghost-dead.txt"), "lorem");
        write(source.resolve("ghost-alive.txt"), "lorem");
        indexAndOpen();

        // delete one file AFTER indexing — index now holds a ghost
        Files.delete(source.resolve("ghost-dead.txt"));

        List<String> ns = names(detailed("ghost", 50).results);
        assertEquals(Collections.singletonList("ghost-alive.txt"), ns,
                "confirmed-deleted file must not be displayed");

        waitForPurge();
        ns = names(detailed("ghost", 50).results);
        assertEquals(Collections.singletonList("ghost-alive.txt"), ns,
                "ghost doc must be gone from the index after async purge");
    }

    @Test
    void shouldRefillPageFromLaterHits() throws Exception {
        // page size 3 with 4 live hits; deleting the top-ranked one must
        // still return a full page via refill, not a 2-hit page
        for (int i = 1; i <= 4; i++) {
            write(source.resolve(String.format("zzrefill-%d.txt", i)), "lorem");
        }
        indexAndOpen();

        SearchOutcome before = detailed("zzrefill", 3);
        assertEquals(3, before.results.size());
        assertEquals(TriState.TRUE, before.hasMore);

        // delete the FIRST hit so the refill must reach into the tail
        String topName = before.results.get(0).name;
        Files.delete(source.resolve(topName));

        SearchOutcome after = detailed("zzrefill", 3);
        assertEquals(3, after.results.size(), "page must be refilled to limit");
        assertFalse(names(after.results).contains(topName));
        assertEquals(TriState.FALSE, after.hasMore,
                "3 valid of 3 remaining hits: proven complete");
    }

    @Test
    void shouldClassifyUnreachableAndInvalidPathsAsKept() {
        // confirmed deletion: file gone, parent alive
        assertTrue(LuceneSearchService.isDefinitelyDeleted(
                source.resolve("gone.txt").toString()),
                "missing file under a live parent is a confirmed ghost");

        // file + parent gone, source root alive → still a confirmed ghost
        assertTrue(LuceneSearchService.isDefinitelyDeleted(
                source.resolve("gone-dir").resolve("inner.txt").toString()),
                "ancestor walk must reach a live root to confirm deletion");

        // no reachable ancestor (offline/unmounted share): UNC host chain
        // cannot be proven gone → kept
        assertFalse(LuceneSearchService.isDefinitelyDeleted(
                "\\\\definitely-not-a-real-host.invalid\\share\\f.txt"),
                "unreachable source must be kept, not purged");

        // invalid path strings can never be proven deleted
        assertFalse(LuceneSearchService.isDefinitelyDeleted("invalid:::path???"),
                "unparseable path must be kept");

        // existing file is alive
        assertFalse(LuceneSearchService.isDefinitelyDeleted(
                fixtureRoot.toString()));
    }

    @Test
    void shouldBoundReachabilityAtTheSourceRoot() {
        // source-bounded: same deletions confirmed against a live source root
        assertTrue(LuceneSearchService.isDefinitelyDeleted(
                source.resolve("gone.txt").toString(), source),
                "live source root confirms the deletion");

        // a configured root that itself is gone → offline-source semantics:
        // the walk must stop at the root, never conclude deletion from the
        // still-alive filesystem root above it
        Path darkRoot = fixtureRoot.resolve("dark-source"); // never created
        assertFalse(LuceneSearchService.isDefinitelyDeleted(
                darkRoot.resolve("deep").resolve("f.txt").toString(), darkRoot),
                "vanished source root must keep its docs (offline, not deleted)");
        // same path with NO source bound still walks to a live ancestor —
        // the bound is what makes offline-vs-deleted distinguishable
        assertTrue(LuceneSearchService.isDefinitelyDeleted(
                darkRoot.resolve("deep").resolve("f.txt").toString()),
                "unbounded walk reaches the live fixtureRoot → confirmed ghost");

        // RG-08: the vanished path IS the source root itself — the walk must
        // not escape above the root to find a live ancestor and wrongly
        // confirm the root's FOLDER doc as deleted
        assertFalse(LuceneSearchService.isDefinitelyDeleted(
                darkRoot.toString(), darkRoot),
                "a vanished source-root path itself is unreachable, not deleted");
        // but an actually-deleted live-root dir IS still confirmable
        assertTrue(LuceneSearchService.isDefinitelyDeleted(
                source.resolve("gone-dir").toString(), source),
                "deleted dir under a live source root is still a ghost");
    }

    @Test
    void shouldReturnFalseWhenEveryCandidateIsAGhost() throws Exception {
        // 505 deleted hits — beyond the old candidate-cap semantics. The
        // cursor must now walk the entire branch to the tail and report
        // proven exhaustion (FALSE), not stop early on a budget.
        int n = 505;
        for (int i = 0; i < n; i++) {
            write(source.resolve(String.format("capghost-%03d.txt", i)), "lorem");
        }
        indexAndOpen();
        for (int i = 0; i < n; i++) {
            Files.delete(source.resolve(String.format("capghost-%03d.txt", i)));
        }

        // preemptive watchdog: a cursor regression would loop forever, so the
        // assertion runs on a separate thread and fails fast instead of
        // hanging the whole suite
        SearchOutcome o = assertTimeoutPreemptively(java.time.Duration.ofSeconds(60),
                () -> detailed("capghost", 50));
        assertTrue(o.results.isEmpty(),
                "no displayable result may survive an all-ghost candidate list");
        assertEquals(TriState.FALSE, o.hasMore,
                "whole branch examined → FALSE, never a fabricated verdict");
    }

    @Test
    void shouldWalkPast1024GhostsToReachValidTail() throws Exception {
        // Regression from the handoff: the first 1024 hits are ghosts and
        // valid hits sit behind them — the cursor must keep advancing rather
        // than re-reading the same top-N window, and refill must reach the
        // valid tail (fewer than limit → FALSE).
        int ghosts = 1024;
        for (int i = 0; i < ghosts; i++) {
            write(source.resolve(String.format("zghost-%04d.txt", i)), "lorem");
        }
        write(source.resolve("tail-a.txt"), "lorem");
        write(source.resolve("tail-b.txt"), "lorem");
        write(source.resolve("tail-c.txt"), "lorem");
        indexAndOpen();
        for (int i = 0; i < ghosts; i++) {
            Files.delete(source.resolve(String.format("zghost-%04d.txt", i)));
        }

        SearchOutcome o = assertTimeoutPreemptively(java.time.Duration.ofSeconds(60),
                () -> detailed("lorem", 50));
        assertEquals(3, o.results.size(), "refill must reach the valid tail past 1024 ghosts");
        assertEquals(TriState.FALSE, o.hasMore,
                "every candidate examined: 3 valid < limit → FALSE");
    }

    @Test
    void shouldKeepResultWhenParentAlsoGone() throws Exception {
        // whole subtree deleted: file + its parent dir both vanished, but the
        // source root still exists → confirmed deletion → filtered
        Path sub = source.resolve("gone-dir");
        Files.createDirectories(sub);
        write(sub.resolve("inner.txt"), "lorem");
        indexAndOpen();

        Files.delete(sub.resolve("inner.txt"));
        Files.delete(sub);

        // "inner" still matches the ghost doc only — with ancestor walk the
        // surviving source root proves real deletion → result is filtered
        List<String> ns = names(detailed("inner", 50).results);
        assertTrue(ns.isEmpty(),
                "file whose deleted parent chain reaches a live root must be dropped");
    }

    @Test
    void shouldFlagUnverifiedWhenSourceRootGoesDark() throws Exception {
        write(source.resolve("dark-hit.txt"), "lorem");
        indexAndOpen();

        // Whole source root vanishes (share unmounted). The file can neither
        // be proven deleted nor verified alive — it is NOT shown (unverified
        // entries never fill the display quota) but is never purged either;
        // the outcome must explicitly flag the uncertainty instead.
        deleteRecursively(source);
        assertFalse(Files.exists(source));

        SearchOutcome o = detailed("dark", 50);
        assertEquals(0, o.results.size(),
                "unverifiable hits stay out of the result quota entirely");
        assertTrue(o.hasUnverified,
                "unverifiable hits must surface the explicit unverified flag");

        waitForPurge();
        SearchOutcome again = detailed("dark", 50);
        assertEquals(0, again.results.size(),
                "offline source entries stay hidden — and, crucially, the "
                + "next assertion proves they were never purged");
        assertTrue(again.hasUnverified);
    }

    @Test
    void shouldNotPurgeFileRecreatedBeforePurgeLock() throws Exception {
        write(source.resolve("revived.txt"), "lorem");
        indexAndOpen();
        Files.delete(source.resolve("revived.txt"));

        // Hold the write lock across the search that detects the ghost, then
        // recreate the file before the purge can acquire it — the queued
        // purge must re-verify under the lock and keep the revived file.
        // The hold exceeds PURGE_RECHECK_AFTER_MS so the in-lock recheck is
        // exercised deterministically.
        org.abitware.docfinder.index.IndexWriteCoordinator.run(() -> {
            SearchOutcome o = detailed("revived", 50);
            assertTrue(o.results.isEmpty(), "deleted file is filtered now");
            try {
                write(source.resolve("revived.txt"), "lorem");
                Thread.sleep(400);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        waitForPurge();
        SearchOutcome after = detailed("revived", 50);
        assertEquals(1, after.results.size(),
                "a file recreated while the purge waited on the write lock must survive");
    }

    @Test
    void shouldResurrectFileRestoredAfterPurge() throws Exception {
        write(source.resolve("revive-me.txt"), "lorem");
        indexAndOpen();
        Files.delete(source.resolve("revive-me.txt"));

        // ghost detected + purged — the doc leaves the index
        assertTrue(detailed("revive", 50).results.isEmpty());
        waitForPurge();

        // restore the file with NO watcher coverage — only the tombstone
        // registry + resurrection pass can bring the doc back
        write(source.resolve("revive-me.txt"), "lorem");

        // each search paces a resurrection kick; poll until it lands
        boolean found = false;
        for (int i = 0; i < 10 && !found; i++) {
            SearchOutcome o = detailed("revive", 50);
            found = o.results.stream().anyMatch(r -> "revive-me.txt".equals(r.name));
            if (!found) {
                Thread.sleep(400);
            }
        }
        assertTrue(found, "a file restored after purge must be resurrected into the index");
    }

    private static void deleteRecursively(Path p) throws Exception {
        if (!Files.exists(p)) {
            return;
        }
        try (java.util.stream.Stream<Path> s = Files.walk(p)) {
            s.sorted(java.util.Comparator.reverseOrder())
                    .forEach(f -> {
                        try {
                            Files.delete(f);
                        } catch (Exception ignore) {
                        }
                    });
        }
    }

    @Test
    void shouldStillFilterDeletedFolderDoc() throws Exception {
        Path reports = source.resolve("repgone");
        Files.createDirectories(reports);
        write(reports.resolve("inner.txt"), "lorem");
        indexAndOpen();

        Files.delete(reports.resolve("inner.txt"));
        Files.delete(reports); // folder doc now ghosts too

        SearchOutcome o = service.searchDetailed(new SearchRequest(
                "repgone", 50, null, SearchScope.FOLDER, MatchMode.FUZZY));
        assertTrue(o.results.isEmpty(),
                "deleted directory's own doc must be filtered in FOLDER scope");
    }
}
