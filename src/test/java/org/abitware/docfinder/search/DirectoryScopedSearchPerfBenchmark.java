package org.abitware.docfinder.search;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.FSDirectory;
import org.abitware.docfinder.util.PathScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P-01 performance observation (A7 / tasks 9.2): compares hot-query latency
 * of directory-scoped vs equivalent unscoped searches on the same index.
 *
 * Fixture per acceptance.md P-01: 100k file metadata docs + directory docs,
 * 10k of which carry small text content. Docs are written directly with the
 * production field layout (no disk files — metadata fixture), recorded as
 * evidence. Timings are reported, NOT asserted; the degradation gate
 * (scoped p95 > 2x unscoped p95 AND +200 ms) is printed as an explicit
 * verdict line for review, not a hard assertion on shared hardware.
 */
class DirectoryScopedSearchPerfBenchmark {

    private static final int FILE_DOCS = 100_000;
    private static final int CONTENT_DOCS = 10_000;
    private static final int DIR_COUNT = 1_000;
    private static final int WARMUP = 10;
    private static final int SAMPLES = 100;

    @TempDir
    Path fixtureRoot;

    private static Document fileDoc(Path src, int i, boolean withContent) {
        Path dir = src.resolve("dir" + (i % DIR_COUNT));
        String p = dir.resolve("file" + i + ".txt").toString();
        Document d = new Document();
        String name = "file" + i + ".txt";
        d.add(new StringField("path", p, Field.Store.YES));
        d.add(new StringField("path_scope",
                PathScope.normalizeIndexedPath(Paths.get(p)), Field.Store.NO));
        d.add(new TextField("name", name, Field.Store.YES));
        d.add(new StringField("name_raw", name.toLowerCase(java.util.Locale.ROOT),
                Field.Store.YES));
        d.add(new StringField("ext", "txt", Field.Store.YES));
        d.add(new StringField("kind", "file", Field.Store.YES));
        d.add(new LongPoint("mtime_l", 1_700_000_000_000L));
        d.add(new StoredField("mtime", 1_700_000_000_000L));
        d.add(new StoredField("ctime", 0L));
        d.add(new StoredField("atime", 0L));
        d.add(new StoredField("size", withContent ? 128L : 0L));
        if (withContent) {
            d.add(new TextField("content",
                    "alpha beta gamma common search token " + i, Field.Store.NO));
        }
        return d;
    }

    private static Document dirDoc(Path dir) {
        Document d = new Document();
        String p = dir.toString();
        String name = dir.getFileName() == null ? p : dir.getFileName().toString();
        d.add(new StringField("path", p, Field.Store.YES));
        d.add(new StringField("path_scope",
                PathScope.normalizeIndexedPath(dir), Field.Store.NO));
        d.add(new TextField("name", name, Field.Store.YES));
        d.add(new StringField("name_raw", name.toLowerCase(java.util.Locale.ROOT),
                Field.Store.YES));
        d.add(new StringField("kind", "folder", Field.Store.YES));
        d.add(new LongPoint("mtime_l", 1_700_000_000_000L));
        d.add(new StoredField("mtime", 1_700_000_000_000L));
        d.add(new StoredField("ctime", 0L));
        d.add(new StoredField("atime", 0L));
        d.add(new StoredField("size", 0L));
        return d;
    }

    private static double pct(List<Long> ns, double p) {
        List<Long> s = new ArrayList<>(ns);
        Collections.sort(s);
        return s.get(Math.min(s.size() - 1, (int) (s.size() * p))) / 1_000_000.0;
    }

    private List<Long> measure(LuceneSearchService svc, String dir) {
        SearchRequest req = new SearchRequest("alpha", 50, null,
                SearchScope.ALL, MatchMode.FUZZY, dir);
        for (int i = 0; i < WARMUP; i++) {
            assertFalse(svc.search(req).isEmpty());
        }
        List<Long> times = new ArrayList<>();
        for (int i = 0; i < SAMPLES; i++) {
            long t = System.nanoTime();
            svc.search(req);
            times.add(System.nanoTime() - t);
        }
        return times;
    }

    @Test
    void benchmarkScopedVsUnscoped() throws Exception {
        Path src = fixtureRoot.resolve("SrcRoot");
        Files.createDirectories(src);
        Path index = fixtureRoot.resolve("idx-p01");
        Files.createDirectories(index);

        StringBuilder report = new StringBuilder("\n===== P-01 scoped vs unscoped =====\n");
        report.append(String.format(
                "fixture: %d file docs (%d with content), %d dir docs, direct IndexWriter%n",
                FILE_DOCS, CONTENT_DOCS, DIR_COUNT + 1));
        report.append(String.format("jvm=%s os=%s%n",
                System.getProperty("java.version"), System.getProperty("os.name")));

        long t0 = System.nanoTime();
        try (IndexWriter w = new IndexWriter(FSDirectory.open(index),
                new IndexWriterConfig(new StandardAnalyzer()))) {
            w.addDocument(dirDoc(src));
            for (int i = 0; i < DIR_COUNT; i++) {
                w.addDocument(dirDoc(src.resolve("dir" + i)));
            }
            for (int i = 0; i < FILE_DOCS; i++) {
                w.addDocument(fileDoc(src, i, i < CONTENT_DOCS));
            }
            w.commit();
        }
        report.append(String.format("index build: %.0f ms%n",
                (System.nanoTime() - t0) / 1_000_000.0));
        long indexBytes = Files.walk(index)
                .filter(Files::isRegularFile)
                .mapToLong(f -> {
                    try { return Files.size(f); } catch (Exception e) { return 0; }
                }).sum();
        report.append(String.format("index size: %.1f MB%n", indexBytes / 1048576.0));

        org.abitware.docfinder.index.IndexSettings settings =
                new org.abitware.docfinder.index.IndexSettings();
        LuceneSearchService svc = new LuceneSearchService(index, settings,
                () -> Collections.singletonList(src));
        try {

            String scopedDir = src.resolve("dir0").toString();

            List<Long> cold = measure(svc, scopedDir); // cold-ish first pass recorded by warmup run
            List<Long> scoped = cold; // after warmup, this IS the hot sample
            List<Long> unscoped = measure(svc, null);
            // re-measure scoped after unscoped for a balanced hot sample
            List<Long> scoped2 = measure(svc, scopedDir);

            List<Long> scopedAll = new ArrayList<>(scoped);
            scopedAll.addAll(scoped2);

            double sp50 = pct(scopedAll, 0.50);
            double sp95 = pct(scopedAll, 0.95);
            double up50 = pct(unscoped, 0.50);
            double up95 = pct(unscoped, 0.95);

            report.append(String.format("unscoped 'alpha' hot: p50=%.2f ms p95=%.2f ms (n=%d)%n",
                    up50, up95, unscoped.size()));
            report.append(String.format("scoped   'alpha' hot: p50=%.2f ms p95=%.2f ms (n=%d)%n",
                    sp50, sp95, scopedAll.size()));

            boolean degraded = sp95 > 2 * up95 && (sp95 - up95) > 200;
            report.append(String.format(
                    "P-01 gate: %s (scoped p95 > 2x unscoped AND +>200ms would flag P1 investigation)%n",
                    degraded ? "DEGRADED — needs investigation" : "OK"));
        } finally {
            svc.close();
        }

        report.append("===================================\n");
        System.out.println(report);
    }
}
