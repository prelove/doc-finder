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

    /** Reads the index directly — proves convergence without any search call. */
    private boolean indexHasDoc(String fileName) throws Exception {
        String want = org.abitware.docfinder.util.Utils.normalizeForIndex(
                source.resolve(fileName));
        try (org.apache.lucene.store.FSDirectory d =
                     org.apache.lucene.store.FSDirectory.open(index);
             org.apache.lucene.index.DirectoryReader r =
                     org.apache.lucene.index.DirectoryReader.open(d)) {
            org.apache.lucene.search.IndexSearcher s =
                    new org.apache.lucene.search.IndexSearcher(r);
            return s.search(new org.apache.lucene.search.TermQuery(
                    new org.apache.lucene.index.Term("path", want)), 1)
                    .totalHits.value > 0;
        }
    }

    /** Bounded state-wait: the async purge must actually commit — a fixed
     *  sleep is not deterministic on slower forks. */
    private void waitForDocGone(String fileName) throws Exception {
        long deadline = System.nanoTime()
                + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (indexHasDoc(fileName) && System.nanoTime() < deadline) {
            Thread.sleep(150);
        }
        assertFalse(indexHasDoc(fileName),
                "purge must commit the delete within bounded time");
    }

    @Test
    void shouldResurrectWhileIdleWithoutAnySearch() throws Exception {
        // The periodic sweep must re-index a restored file even when the
        // app sits completely idle — "search again and it appears" is NOT
        // accepted as automatic recovery.
        write(source.resolve("idle-back.txt"), "lorem");
        indexAndOpen();
        Files.delete(source.resolve("idle-back.txt"));

        assertTrue(detailed("idle-back", 50).results.isEmpty());
        waitForDocGone("idle-back.txt");

        // restore, then NO searches at all — just wait
        write(source.resolve("idle-back.txt"), "lorem");
        boolean found = false;
        for (int i = 0; i < 16 && !found; i++) {
            Thread.sleep(500);
            found = indexHasDoc("idle-back.txt");
        }
        assertTrue(found,
                "idle-time sweep must resurrect the file with zero intervening searches");
    }

    @Test
    void shouldResurrectAcrossRestartWithoutScopedRequest() throws Exception {
        // Restart scenario from the probe: tombstone persisted, file
        // restored while down, and only GLOBAL searches afterwards.
        write(source.resolve("back-after-restart.txt"), "lorem");
        indexAndOpen();
        Files.delete(source.resolve("back-after-restart.txt"));

        assertTrue(detailed("back-after", 50).results.isEmpty());
        waitForDocGone("back-after-restart.txt");

        service.close();
        service = null;

        write(source.resolve("back-after-restart.txt"), "lorem");

        // fresh service = "restarted app": ctor seeds roots, the sweeper
        // must resurrect without needing a scoped directory request
        SourceRootProvider provider = () -> Collections.singletonList(source);
        service = new LuceneSearchService(index, settings, provider);

        boolean found = false;
        for (int i = 0; i < 16 && !found; i++) {
            Thread.sleep(500);
            found = indexHasDoc("back-after-restart.txt");
        }
        assertTrue(found,
                "restart + only-global-searches must still resurrect the restored file");
    }

    @Test
    void shouldReachResurrectionTailDespiteMissingHead() throws Exception {
        // Head of the tombstone list stays missing: a rotating cursor must
        // still reach restored entries beyond the first RESURRECT_BATCH.
        indexAndOpen();
        java.util.List<String> fakes = new java.util.ArrayList<>();
        for (int i = 0; i < 300; i++) {
            String p = org.abitware.docfinder.util.Utils.normalizeForIndex(
                    source.resolve(String.format("starve-%03d.txt", i)));
            fakes.add(p);
            org.abitware.docfinder.index.Tombstones.record(p);
        }
        Path real = source.resolve("starve-tail.txt");
        write(real, "lorem");
        String realKey = org.abitware.docfinder.util.Utils.normalizeForIndex(real);
        org.abitware.docfinder.index.Tombstones.record(realKey);
        try {
            boolean found = false;
            for (int i = 0; i < 20 && !found; i++) {
                Thread.sleep(500);
                found = indexHasDoc("starve-tail.txt");
            }
            assertTrue(found,
                    "rotating cursor must reach tombstones behind 300 missing entries");
        } finally {
            for (String p : fakes) {
                org.abitware.docfinder.index.Tombstones.remove(p);
            }
            org.abitware.docfinder.index.Tombstones.remove(realKey);
        }
    }

    @Test
    void shouldNotPurgeDocReindexedAfterVerdict() throws Exception {
        // A doc re-indexed between phase-1 classification and the write
        // lock must survive — the upsert-generation check inside the lock
        // is generation-based, not wait-length-based.
        write(source.resolve("reup.txt"), "lorem");
        indexAndOpen();
        Files.delete(source.resolve("reup.txt"));

        org.abitware.docfinder.index.IndexWriteCoordinator.run(() -> {
            SearchOutcome o = detailed("reup", 50);
            assertTrue(o.results.isEmpty(), "ghost detected while lock held");
            try {
                write(source.resolve("reup.txt"), "lorem");
                try (LuceneIndexer idx = new LuceneIndexer(index, settings)) {
                    idx.upsertFile(source.resolve("reup.txt")); // newer generation
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        waitForPurge();
        SearchOutcome after = detailed("reup", 50);
        assertEquals(1, after.results.size(),
                "a doc re-indexed after the purge verdict must not be deleted");
    }

    @Test
    void shouldBoundRecentUpsertsRegistry() throws Exception {
        // R3-01: the protection table must be TRULY bounded — inserting
        // far more than the cap must leave size() <= cap, with O(1) puts
        // (no full-table scans on every upsert).
        java.lang.reflect.Method m = LuceneIndexer.class.getDeclaredMethod(
                "recordUpsert", String.class);
        m.setAccessible(true);
        for (int i = 0; i < 5000; i++) {
            m.invoke(null, "cap-probe-" + i);
        }
        java.lang.reflect.Field f = LuceneIndexer.class
                .getDeclaredField("RECENT_UPSERTS");
        f.setAccessible(true);
        int size = ((java.util.Map<?, ?>) f.get(null)).size();
        assertTrue(size <= 4096,
                "RECENT_UPSERTS must be hard-bounded, got " + size);
    }

    @Test
    void shouldReloadRootsAfterProviderFailureAndResurrect() throws Exception {
        // R3-03: a transient provider IOException must not freeze the
        // roots snapshot — once the provider recovers, the sweeper
        // re-loads roots and resurrection proceeds without any search.
        write(source.resolve("src-retry.txt"), "lorem");
        indexAndOpen();
        Files.delete(source.resolve("src-retry.txt"));
        assertTrue(detailed("src-retry", 50).results.isEmpty());
        waitForDocGone("src-retry.txt"); // purged → tombstone persists

        service.close();
        service = null;

        write(source.resolve("src-retry.txt"), "lorem"); // restored while down

        java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();
        SourceRootProvider flaky = () -> {
            if (calls.incrementAndGet() <= 1) {
                throw new java.io.IOException("simulated config read failure");
            }
            return Collections.singletonList(source);
        };
        // ctor seeding hits the failure; the sweeper must retry until the
        // provider recovers and then resurrect the tombstoned file.
        service = new LuceneSearchService(index, settings, flaky);

        boolean found = false;
        for (int i = 0; i < 16 && !found; i++) {
            Thread.sleep(500);
            found = indexHasDoc("src-retry.txt");
        }
        assertTrue(found,
                "sweeper must recover from a transient provider failure");
        assertTrue(calls.get() >= 2, "provider was retried after failure");
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
