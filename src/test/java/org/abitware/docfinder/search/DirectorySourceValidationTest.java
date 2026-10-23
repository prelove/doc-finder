package org.abitware.docfinder.search;

import org.abitware.docfinder.index.IndexSettings;
import org.abitware.docfinder.index.LuceneIndexer;
import org.abitware.docfinder.index.SourceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A3 boundary validation driven by a real strict sources.txt file via
 * {@code new SourceManager(baseDir).loadSearchRoots()} rather than a stubbed
 * provider, plus snapshot skipped-line reporting.
 */
@Execution(ExecutionMode.SAME_THREAD)
class DirectorySourceValidationTest {

    @TempDir
    Path fixtureRoot;

    private Path indexedSource(String name) throws Exception {
        Path src = fixtureRoot.resolve(name);
        Files.createDirectories(src);
        Files.write(src.resolve("alpha.txt"), "alpha".getBytes("UTF-8"));
        return src;
    }

    /** Provider backed by a real sources.txt in a temp config dir. */
    private LuceneSearchService serviceFor(Path index, Path configDir) throws Exception {
        SourceManager sm = new SourceManager(configDir);
        return new LuceneSearchService(index, new IndexSettings(), sm::loadSearchRoots);
    }

    private static SearchException scopedFail(LuceneSearchService svc,
                                              String dir) {
        return assertThrows(SearchException.class,
                () -> svc.search(new SearchRequest("alpha", 10, null,
                        SearchScope.ALL, MatchMode.FUZZY, dir)));
    }

    @Test
    void shouldDistinguishConfigFailureFromUncoveredDirectory() throws Exception {
        Path src = indexedSource("SrcA");
        Path index = fixtureRoot.resolve("index");
        try (LuceneIndexer indexer = new LuceneIndexer(index, new IndexSettings())) {
            indexer.indexFolder(src);
        }

        // all-invalid data lines → SOURCE_CONFIG_UNAVAILABLE
        Path cfgBad = fixtureRoot.resolve("cfg-bad");
        Files.createDirectories(cfgBad);
        Files.write(cfgBad.resolve("sources.txt"),
                "not-absolute\ntoo|many|cols\n".getBytes(StandardCharsets.UTF_8));
        LuceneSearchService svc1 = serviceFor(index, cfgBad);
        try {
            assertEquals(SearchException.Code.SOURCE_CONFIG_UNAVAILABLE,
                    scopedFail(svc1, src.toString()).getCode());
        } finally {
            svc1.close();
        }

        // valid file, directory not covered → DIRECTORY_NOT_INDEXED
        Path cfgOk = fixtureRoot.resolve("cfg-ok");
        Files.createDirectories(cfgOk);
        Path other = fixtureRoot.resolve("Elsewhere");
        Files.createDirectories(other);
        Files.write(cfgOk.resolve("sources.txt"),
                (other.toString() + "\n").getBytes(StandardCharsets.UTF_8));
        LuceneSearchService svc2 = serviceFor(index, cfgOk);
        try {
            assertEquals(SearchException.Code.DIRECTORY_NOT_INDEXED,
                    scopedFail(svc2, src.toString()).getCode());
        } finally {
            svc2.close();
        }

        // missing sources file = empty configuration → also DIRECTORY_NOT_INDEXED
        Path cfgMissing = fixtureRoot.resolve("cfg-missing");
        Files.createDirectories(cfgMissing);
        LuceneSearchService svc3 = serviceFor(index, cfgMissing);
        try {
            assertEquals(SearchException.Code.DIRECTORY_NOT_INDEXED,
                    scopedFail(svc3, src.toString()).getCode());
        } finally {
            svc3.close();
        }
    }

    @Test
    void shouldExposeSkippedLineNumbersInSnapshotOnly() throws Exception {
        Path src = indexedSource("SrcB");
        Path cfg = fixtureRoot.resolve("cfg-mixed");
        Files.createDirectories(cfg);
        Files.write(cfg.resolve("sources.txt"),
                ("# comment\n\n" + "badrow\n" + src.toString() + "|0\n")
                        .getBytes(StandardCharsets.UTF_8));
        SourceManager.SearchRootsSnapshot snap =
                new SourceManager(cfg).loadSearchRootsSnapshot();
        assertEquals(1, snap.roots.size());
        assertEquals(java.util.Collections.singletonList(3), snap.skippedLineNumbers);
    }

    @Test
    void shouldAcceptDescendantAndOverlappingSources() throws Exception {
        Path src = indexedSource("SrcC");
        Path deep = src.resolve("a").resolve("b");
        Files.createDirectories(deep);
        Files.write(deep.resolve("alpha2.txt"), "alpha".getBytes("UTF-8"));
        Path index = fixtureRoot.resolve("index2");
        try (LuceneIndexer indexer = new LuceneIndexer(index, new IndexSettings())) {
            indexer.indexFolder(src);
        }
        Path cfg = fixtureRoot.resolve("cfg-c");
        Files.createDirectories(cfg);
        Files.write(cfg.resolve("sources.txt"),
                (src.toString() + "\n").getBytes(StandardCharsets.UTF_8));
        LuceneSearchService svc = serviceFor(index, cfg);
        try {
            List<SearchResult> r = svc.search(new SearchRequest("alpha", 10, null,
                    SearchScope.ALL, MatchMode.FUZZY, deep.toString()));
            assertEquals(1, r.size());
            assertEquals("alpha2.txt", r.get(0).name);
        } finally {
            svc.close();
        }
    }
}
