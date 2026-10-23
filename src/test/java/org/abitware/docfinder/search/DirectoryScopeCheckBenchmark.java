package org.abitware.docfinder.search;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P-00 microbenchmark (A4 / tasks 5.1): measures the real cost of
 * ensureDirectoryScopeSupported over ~100k path_scope docs — first call,
 * repeated call on the same reader, and first call after a refresh — plus
 * correctness checks contrasting numDocs vs maxDoc. Timings are recorded as
 * evidence, NOT asserted: this test only fails on wrong verdicts.
 */
class DirectoryScopeCheckBenchmark {

    private static final int DOC_COUNT = 100_000;

    @TempDir
    Path fixtureRoot;

    private static Document doc(Path base, int i, boolean withScope) {
        String p = base.resolve("d" + (i % 50)).resolve("f" + i + ".txt").toString();
        Document d = new Document();
        d.add(new StringField("path", p, Field.Store.YES));
        d.add(new StringField("kind", "file", Field.Store.YES));
        if (withScope) {
            d.add(new StringField("path_scope",
                    org.abitware.docfinder.util.PathScope.normalizeIndexedPath(
                            java.nio.file.Paths.get(p)), Field.Store.NO));
        }
        return d;
    }

    private static double median(List<Long> ns) {
        List<Long> s = new ArrayList<>(ns);
        Collections.sort(s);
        return s.get(s.size() / 2) / 1_000_000.0;
    }

    private static double p95(List<Long> ns) {
        List<Long> s = new ArrayList<>(ns);
        Collections.sort(s);
        return s.get(Math.min(s.size() - 1, (int) (s.size() * 0.95))) / 1_000_000.0;
    }

    @Test
    void benchmarkScopeCheck() throws Exception {
        Path src = fixtureRoot.resolve("src");
        Files.createDirectories(src);
        Path index = fixtureRoot.resolve("idx-bench");
        Files.createDirectories(index);

        StringBuilder report = new StringBuilder("\n===== P-00 scope-check microbenchmark =====\n");
        report.append("docs=").append(DOC_COUNT).append('\n');

        // --- Scenario: all-new index (full path_scope coverage) ---
        long t0 = System.nanoTime();
        try (IndexWriter w = new IndexWriter(FSDirectory.open(index),
                new IndexWriterConfig(new StandardAnalyzer()))) {
            for (int i = 0; i < DOC_COUNT; i++) {
                w.addDocument(doc(src, i, true));
            }
            w.commit();
        }
        report.append(String.format("index build: %.0f ms%n",
                (System.nanoTime() - t0) / 1_000_000.0));

        try (IndexReader r = DirectoryReader.open(FSDirectory.open(index))) {
            IndexSearcher s = new IndexSearcher(r);
            report.append(String.format("leaves=%d maxDoc=%d numDocs=%d%n",
                    r.leaves().size(), r.maxDoc(), r.numDocs()));

            // first call (cold-ish)
            long t = System.nanoTime();
            LuceneSearchService.ensureDirectoryScopeSupported(s);
            report.append(String.format("first call: %.2f ms%n",
                    (System.nanoTime() - t) / 1_000_000.0));

            // repeated calls on the same reader (same cached verdict path at
            // service level is measured implicitly; here the raw check)
            List<Long> times = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                long x = System.nanoTime();
                LuceneSearchService.ensureDirectoryScopeSupported(s);
                times.add(System.nanoTime() - x);
            }
            report.append(String.format("same-reader repeat: p50=%.2f ms p95=%.2f ms%n",
                    median(times), p95(times)));

            // exact fallback cost directly (what an inconclusive leaf pays)
            t = System.nanoTime();
            org.apache.lucene.search.BooleanQuery.Builder missing =
                    new org.apache.lucene.search.BooleanQuery.Builder();
            missing.add(new org.apache.lucene.search.MatchAllDocsQuery(),
                    org.apache.lucene.search.BooleanClause.Occur.MUST);
            missing.add(new org.apache.lucene.search.PrefixQuery(
                    new Term("path_scope", "")),
                    org.apache.lucene.search.BooleanClause.Occur.MUST_NOT);
            assertEquals(0, s.search(missing.build(), 1).totalHits.value);
            report.append(String.format("exact fallback query (no missing): %.2f ms%n",
                    (System.nanoTime() - t) / 1_000_000.0));
        }

        // --- Scenario: deleted docs → docCount == maxDoc fast path on leaf ---
        Path index2 = fixtureRoot.resolve("idx-del");
        Files.createDirectories(index2);
        try (IndexWriter w = new IndexWriter(FSDirectory.open(index2),
                new IndexWriterConfig(new StandardAnalyzer()))) {
            for (int i = 0; i < 1000; i++) {
                w.addDocument(doc(src, i, true));
            }
            w.commit();
            w.deleteDocuments(new Term("path",
                    src.resolve("d0").resolve("f0.txt").toString()));
            w.commit();
        }
        try (IndexReader r = DirectoryReader.open(FSDirectory.open(index2))) {
            IndexSearcher s = new IndexSearcher(r);
            report.append(String.format(
                    "deleted-doc index: maxDoc=%d numDocs=%d (fast path may not trigger, exact fallback used)%n",
                    r.maxDoc(), r.numDocs()));
            long t = System.nanoTime();
            LuceneSearchService.ensureDirectoryScopeSupported(s); // must pass
            report.append(String.format("deleted-doc check: %.2f ms%n",
                    (System.nanoTime() - t) / 1_000_000.0));
        }

        // --- Scenario: mixed index verdict correctness ---
        Path index3 = fixtureRoot.resolve("idx-mixed");
        Files.createDirectories(index3);
        try (IndexWriter w = new IndexWriter(FSDirectory.open(index3),
                new IndexWriterConfig(new StandardAnalyzer()))) {
            for (int i = 0; i < 1000; i++) {
                w.addDocument(doc(src, i, i != 999));
            }
            w.commit();
        }
        try (IndexReader r = DirectoryReader.open(FSDirectory.open(index3))) {
            IndexSearcher s = new IndexSearcher(r);
            SearchException ex = assertThrows(SearchException.class,
                    () -> LuceneSearchService.ensureDirectoryScopeSupported(s));
            assertEquals(SearchException.Code.INDEX_REBUILD_REQUIRED, ex.getCode());
        }

        report.append("=========================================\n");
        System.out.println(report);
    }
}
