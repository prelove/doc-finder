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
