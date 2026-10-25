package org.abitware.docfinder.search;

import org.abitware.docfinder.index.IndexSettings;
import org.abitware.docfinder.index.LuceneIndexer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A3 scoped-search contract: source-boundary validation before syntax, the
 * directory FILTER (root itself only as folder + whole descendant tree),
 * type filtering before Top-N, uniform INVALID_QUERY, and the provider
 * invoked at most once per scoped request.
 */
@Execution(ExecutionMode.SAME_THREAD)
class DirectoryScopedSearchTest {

    @TempDir
    Path fixtureRoot;

    private Path source;
    private Path otherSource;
    private Path index;
    private AtomicInteger providerCalls;
    private LuceneSearchService service;

    @BeforeEach
    void setUp() throws Exception {
        source = fixtureRoot.resolve("ProjectA");
        otherSource = fixtureRoot.resolve("OtherRoot");
        index = fixtureRoot.resolve("index");

        Files.createDirectories(source.resolve("sub").resolve("deep"));
        // sibling-prefix trap: must NOT match a scope of source
        Files.createDirectories(fixtureRoot.resolve("ProjectAB"));
        Files.createDirectories(otherSource);

        write(source.resolve("alpha-report.txt"), "alpha alpha alpha");
        write(source.resolve("sub").resolve("alpha-note.md"), "alpha");
        write(source.resolve("sub").resolve("deep").resolve("alpha-deep.txt"), "alpha");
        write(fixtureRoot.resolve("ProjectAB").resolve("alpha-sibling.txt"), "alpha");
        write(otherSource.resolve("alpha-other.txt"), "alpha");

        IndexSettings settings = new IndexSettings();
        try (LuceneIndexer indexer = new LuceneIndexer(index, settings)) {
            indexer.indexFolders(java.util.Arrays.asList(
                    source, fixtureRoot.resolve("ProjectAB"), otherSource), false);
        }

        providerCalls = new AtomicInteger();
        SourceRootProvider provider = () -> {
            providerCalls.incrementAndGet();
            return java.util.Arrays.asList(source, otherSource);
        };
        service = new LuceneSearchService(index, settings, provider);
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

    private List<SearchResult> scoped(String q, String dir, SearchScope scope) {
        return service.search(new SearchRequest(q, 100, null, scope,
                MatchMode.FUZZY, dir));
    }

    @Test
    void shouldRestrictResultsToSelectedDirectoryTree() {
        List<SearchResult> r = scoped("alpha", source.toString(), SearchScope.ALL);
        assertFalse(r.isEmpty());
        assertTrue(r.stream().allMatch(x -> x.path.replace('\\', '/')
                        .toLowerCase(java.util.Locale.ROOT)
                        .contains("/projecta/")),
                "all results must live under the selected source");
        assertTrue(r.stream().anyMatch(x -> x.name.equals("alpha-deep.txt")),
                "deep descendants are in scope");
        assertTrue(r.stream().noneMatch(x -> x.name.equals("alpha-sibling.txt")),
                "ProjectAB sibling prefix must not leak into ProjectA scope");
        assertTrue(r.stream().noneMatch(x -> x.name.equals("alpha-other.txt")));
    }

    @Test
    void shouldIncludeSelectedRootItselfForFolderSearch() {
        List<SearchResult> r = scoped("ProjectA", source.toString(), SearchScope.FOLDER);
        assertTrue(r.stream().anyMatch(x -> x.directory && x.name.equals("ProjectA")),
                "FOLDER scope must include the selected root directory itself");
        assertTrue(r.stream().allMatch(x -> x.directory));
        // A scope pointing at ProjectAB (not a configured source, not under one)
        // must be rejected even though it exists on disk.
        SearchException ex = assertThrows(SearchException.class,
                () -> scoped("alpha", fixtureRoot.resolve("ProjectAB").toString(),
                        SearchScope.ALL));
        assertEquals(SearchException.Code.DIRECTORY_NOT_INDEXED, ex.getCode());
    }

    @Test
    void shouldRejectDirectoryCoveringOnlyParentsOfSource() {
        // fixtureRoot contains the source but is not itself a configured source.
        SearchException ex = assertThrows(SearchException.class,
                () -> scoped("alpha", fixtureRoot.toString(), SearchScope.ALL));
        assertEquals(SearchException.Code.DIRECTORY_NOT_INDEXED, ex.getCode());
    }

    @Test
    void shouldApplyTypeFilterBeforeLimit() {
        // Directory names score high on the folder query; with limit=1 the old
        // post-Top-N exclusion could return zero files. Query-level MUST_NOT
        // must not let excluded docs consume the limit.
        List<SearchResult> r = service.search(new SearchRequest(
                "alpha", 1, null, SearchScope.ALL, MatchMode.FUZZY,
                source.toString()));
        assertEquals(1, r.size());
        assertFalse(r.get(0).directory);
    }

    @Test
    void shouldReportInvalidDirectoryWithoutFallingBackToGlobal() {
        assertThrows(SearchException.class,
                () -> scoped("alpha", "relative/path", SearchScope.ALL))
                .getCode();
        SearchException ex = assertThrows(SearchException.class,
                () -> scoped("alpha", "not-a-path", SearchScope.ALL));
        assertEquals(SearchException.Code.INVALID_DIRECTORY, ex.getCode());
    }

    @Test
    void shouldInvokeProviderAtMostOncePerRequestAndNotForBlank() {
        scoped("alpha", source.toString(), SearchScope.ALL);
        assertEquals(1, providerCalls.get(),
                "one snapshot per scoped request, reused by the ghost filter");

        scoped("alpha", source.toString(), SearchScope.ALL);
        assertEquals(2, providerCalls.get());

        // unscoped requests load sources lazily on the first existence check
        // (needed for source-bounded reachability), still once per request
        service.search(new SearchRequest("alpha", 10, null));
        assertEquals(3, providerCalls.get());

        service.search(new SearchRequest("", 10, null, SearchScope.ALL,
                MatchMode.FUZZY, source.toString())); // blank query + dir
        assertEquals(3, providerCalls.get(),
                "blank-query requests must not load sources");
    }

    @Test
    void shouldMapProviderIoFailureToSourceConfigUnavailable() throws Exception {
        SourceRootProvider failing = () -> {
            throw new IOException("simulated unreadable sources.txt");
        };
        LuceneSearchService svc = new LuceneSearchService(
                index, new IndexSettings(), failing);
        try {
            SearchException ex = assertThrows(SearchException.class,
                    () -> svc.search(new SearchRequest("alpha", 10, null,
                            SearchScope.ALL, MatchMode.FUZZY, source.toString())));
            assertEquals(SearchException.Code.SOURCE_CONFIG_UNAVAILABLE, ex.getCode());
        } finally {
            svc.close();
        }
    }

    @Test
    void shouldUnifyInvalidQueryWithAndWithoutDirectory() {
        // "report AND (" is a parse error in both modes.
        SearchException global = assertThrows(SearchException.class,
                () -> service.search(new SearchRequest(
                        "report AND (", 10, null)));
        assertEquals(SearchException.Code.INVALID_QUERY, global.getCode());

        SearchException scopedEx = assertThrows(SearchException.class,
                () -> scoped("report AND (", source.toString(), SearchScope.ALL));
        assertEquals(SearchException.Code.INVALID_QUERY, scopedEx.getCode());
    }

    @Test
    void shouldReturnZeroHitsForValidEmptyScope() throws Exception {
        // Valid source, valid index, but no matching documents → empty list,
        // not an error.
        Path empty = fixtureRoot.resolve("EmptySource");
        Files.createDirectories(empty);
        IndexSettings settings = new IndexSettings();
        try (LuceneIndexer indexer = new LuceneIndexer(index, settings)) {
            indexer.indexFolder(empty);
        }
        service.close();
        providerCalls.set(0);
        service = new LuceneSearchService(index, settings,
                () -> java.util.Arrays.asList(source, otherSource, empty));
        List<SearchResult> r = scoped("alpha", empty.toString(), SearchScope.ALL);
        assertTrue(r.isEmpty(), "in-scope zero hits is a success, not a failure");
    }

    @Test
    void shouldLabelExplicitNameQueryGlobally() {
        // QA24-03: an explicit name: query must keep "name" as match source.
        List<SearchResult> hits = service.search(new SearchRequest(
                "name:alpha-report.txt", 100, null, SearchScope.NAME, MatchMode.FUZZY));
        assertEquals(1, hits.size());
        assertEquals("name", hits.get(0).match, "explicit name query must retain name source");
    }

    @Test
    void shouldLabelExplicitNameQueryWithinDirectory() {
        List<SearchResult> hits = scoped("name:alpha-report.txt", source.toString(), SearchScope.NAME);
        assertEquals(1, hits.size());
        assertEquals("name", hits.get(0).match, "scoped explicit name must retain source");
    }

    @Test
    void shouldLabelExplicitNameAndContent() {
        List<SearchResult> hits = scoped("name:alpha-report.txt content:alpha",
                source.toString(), SearchScope.ALL);
        assertEquals(1, hits.size());
        assertEquals("name+content", hits.get(0).match,
                "explicit name + content must expose both sources");
    }

    @Test
    void shouldNotMistakePosixStyleNameInPathForNameHit() {
        // detectMatchType must explain only the keyword query. A directory
        // segment literally containing "name:" must not label a content hit
        // as a name hit.
        List<SearchResult> r = scoped("alpha", source.toString(), SearchScope.CONTENT);
        assertTrue(r.stream().allMatch(x -> "content".equals(x.match)
                        || "name+content".equals(x.match)),
                "CONTENT-scope results report content match source");
    }
}
