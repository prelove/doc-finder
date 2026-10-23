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
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Characterization tests for the existing unscoped search contract, built on a
 * real temporary Lucene index (LuceneIndexer → LuceneSearchService). Fixture
 * source/index live in @TempDir siblings outside user.dir; no real .docfinder,
 * sources.txt or index is touched. These lock current behaviour; they do not
 * codify the known post-Top-N type-filter defect.
 */
@Execution(ExecutionMode.SAME_THREAD)
class LuceneSearchServiceTest {

    @TempDir
    Path fixtureRoot;

    private Path source;
    private Path index;
    private LuceneSearchService service;

    private static final long DATED_MTIME = 1_600_000_000_000L;

    @BeforeEach
    void setUp() throws Exception {
        source = fixtureRoot.resolve("source");
        index = fixtureRoot.resolve("index");
        Files.createDirectories(source.resolve("TargetDir"));

        // name hit only
        write(source.resolve("alpha-name.txt"), "zzz zzz zzz");
        // content hits only
        write(source.resolve("beta.txt"), "alpha alpha alpha");
        write(source.resolve("dated.txt"), "alpha contract");
        write(source.resolve("beta.md"), "alpha");
        write(source.resolve("TargetDir").resolve("inner.txt"), "alpha");

        Files.setLastModifiedTime(source.resolve("dated.txt"),
                FileTime.fromMillis(DATED_MTIME));

        IndexSettings settings = new IndexSettings();
        try (LuceneIndexer indexer = new LuceneIndexer(index, settings)) {
            indexer.indexFolder(source);
        }
        service = new LuceneSearchService(index, settings);
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

    private static List<SearchResult> search(LuceneSearchService svc, String q, int limit,
            FilterState filter, SearchScope scope) {
        return svc.search(new SearchRequest(q, limit, filter, scope, MatchMode.FUZZY));
    }

    @Test
    void shouldReturnEmptyForBlankQuery() {
        assertTrue(search(service, "", 100, null, SearchScope.ALL).isEmpty());
        assertTrue(search(service, "   ", 100, null, SearchScope.ALL).isEmpty());
        assertTrue(search(service, null, 100, null, SearchScope.ALL).isEmpty());
    }

    @Test
    void shouldPreserveUnscopedModesAndScores() {
        List<SearchResult> name = search(service, "alpha", 100, null, SearchScope.NAME);
        assertEquals(1, name.size());
        assertEquals("alpha-name.txt", name.get(0).name);
        assertFalse(name.get(0).directory);

        List<SearchResult> content = search(service, "alpha", 100, null, SearchScope.CONTENT);
        assertTrue(content.stream().noneMatch(r -> "alpha-name.txt".equals(r.name)),
                "CONTENT must not return name-only hits");
        assertTrue(content.stream().anyMatch(r -> "beta.txt".equals(r.name)));

        List<SearchResult> all = search(service, "alpha", 100, null, SearchScope.ALL);
        assertFalse(all.isEmpty());
        assertEquals("alpha-name.txt", all.get(0).name,
                "name hit must outrank content-only hits (name boost)");
        assertTrue(all.get(0).score > 0);
        assertTrue(all.stream().noneMatch(r -> r.directory),
                "ALL must not leak folder results");

        List<SearchResult> folder = search(service, "TargetDir", 100, null, SearchScope.FOLDER);
        assertEquals(1, folder.size());
        assertTrue(folder.get(0).directory);
        assertEquals("TargetDir", folder.get(0).name);
    }

    @Test
    void shouldPreserveFiltersAndLimits() {
        FilterState txtOnly = new FilterState();
        txtOnly.exts.add("txt");
        List<SearchResult> filtered =
                search(service, "alpha", 100, txtOnly, SearchScope.ALL);
        assertFalse(filtered.isEmpty());
        assertTrue(filtered.stream().noneMatch(r -> r.name.endsWith(".md")),
                "ext filter must exclude .md results");

        FilterState dated = new FilterState();
        dated.fromEpochMs = DATED_MTIME - 1000;
        dated.toEpochMs = DATED_MTIME + 1000;
        List<SearchResult> inRange =
                search(service, "alpha", 100, dated, SearchScope.ALL);
        assertEquals(1, inRange.size());
        assertEquals("dated.txt", inRange.get(0).name);

        List<SearchResult> limited = search(service, "alpha", 1, null, SearchScope.ALL);
        assertEquals(1, limited.size());
    }

    @Test
    void shouldReturnRealPathsFromIndex() {
        List<SearchResult> all = search(service, "alpha", 100, null, SearchScope.NAME);
        assertEquals(1, all.size());
        String expected = org.abitware.docfinder.util.Utils
                .normalizeForIndex(source.resolve("alpha-name.txt"));
        assertEquals(expected, all.get(0).path);
    }
}
