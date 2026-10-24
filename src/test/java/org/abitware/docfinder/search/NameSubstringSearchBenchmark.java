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
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * B0/D1 name-search latency observation: 100k file-name metadata docs
 * written directly with the production field layout (no disk files).
 * Measures the new literal FUZZY paths end-to-end through
 * {@link LuceneSearchService#searchDetailed}: NAME single-token substring,
 * NAME multi-word AND, and the ALL dual-list. Timings are reported with
 * JVM/heap/index-size/sample counts recorded, not hard-asserted — the
 * ~100 ms figure is a local observation target, not a build gate.
 */
@Execution(ExecutionMode.SAME_THREAD)
class NameSubstringSearchBenchmark {

    private static final int FILE_DOCS = 100_000;
    private static final int DIR_COUNT = 1_000;
    private static final int WARMUP = 10;
    private static final int SAMPLES = 100;

    @TempDir
    Path fixtureRoot;

    private static Document fileDoc(Path src, int i) {
        Path dir = src.resolve("dir" + (i % DIR_COUNT));
        // Interleave name shapes so tiers are non-degenerate: most docs are
        // generic fileNNNNN.txt; every 997th is report-shaped; every 1531st
        // carries a CJK fragment; every 2089th a symbol token.
        String name;
        if (i % 2089 == 0) {
            name = "spring-core-" + i + ".jar";
        } else if (i % 1531 == 0) {
            name = "支付记录-" + i + ".txt";
        } else if (i % 997 == 0) {
            name = "annual-report-" + i + ".pdf";
        } else {
            name = "file" + i + ".txt";
        }
        String p = dir.resolve(name).toString();
        Document d = new Document();
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
        d.add(new StoredField("size", 128L));
        if (i % 10 == 0) {
            d.add(new TextField("content",
                    "alpha beta report common token " + i, Field.Store.NO));
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

    private List<Long> measure(LuceneSearchService svc, String q, SearchScope scope) {
        SearchRequest req = new SearchRequest(q, 50, null, scope, MatchMode.FUZZY);
        List<Long> times = new ArrayList<>();
        for (int i = 0; i < WARMUP + SAMPLES; i++) {
            long t = System.nanoTime();
            SearchOutcome o = svc.searchDetailed(req);
            assertFalse(o.results.isEmpty(), "fixture query '" + q + "' must hit");
            if (i >= WARMUP) times.add(System.nanoTime() - t);
        }
        return times;
    }

    @Test
    void benchmarkLiteralNameSearch() throws Exception {
        Path src = fixtureRoot.resolve("SrcRoot");
        Files.createDirectories(src);
        Path index = fixtureRoot.resolve("idx-b0");
        Files.createDirectories(index);

        StringBuilder report = new StringBuilder("\n===== B0 literal name-search =====\n");
        report.append(String.format("fixture: %d file docs + %d dir docs, direct IndexWriter%n",
                FILE_DOCS, DIR_COUNT + 1));
        report.append(String.format("jvm=%s vm=%s os=%s%n",
                System.getProperty("java.version"), System.getProperty("java.vm.name"),
                System.getProperty("os.name")));
        Runtime rt = Runtime.getRuntime();
        report.append(String.format("heap: max=%d MB committed=%d MB%n",
                rt.maxMemory() / 1048576, rt.totalMemory() / 1048576));
        report.append(String.format("warmup=%d samples=%d end-to-end searchDetailed()%n",
                WARMUP, SAMPLES));

        long t0 = System.nanoTime();
        try (IndexWriter w = new IndexWriter(FSDirectory.open(index),
                new IndexWriterConfig(new StandardAnalyzer()))) {
            w.addDocument(dirDoc(src));
            for (int i = 0; i < DIR_COUNT; i++) {
                w.addDocument(dirDoc(src.resolve("dir" + i)));
            }
            for (int i = 0; i < FILE_DOCS; i++) {
                w.addDocument(fileDoc(src, i));
            }
            w.commit();
        }
        report.append(String.format("index build: %.0f ms%n", (System.nanoTime() - t0) / 1_000_000.0));
        long indexBytes = Files.walk(index).filter(Files::isRegularFile)
                .mapToLong(f -> { try { return Files.size(f); } catch (Exception e) { return 0; } })
                .sum();
        report.append(String.format("index size: %.1f MB%n", indexBytes / 1048576.0));

        org.abitware.docfinder.index.IndexSettings settings =
                new org.abitware.docfinder.index.IndexSettings();
        LuceneSearchService svc = new LuceneSearchService(index, settings,
                () -> Collections.singletonList(src));
        try {
            Object[][] dims = {
                    {"NAME single literal  'report'", "report", SearchScope.NAME},
                    {"NAME multi-word AND 'annual report'", "annual report", SearchScope.NAME},
                    {"NAME CJK            '支付'", "支付", SearchScope.NAME},
                    {"NAME symbol         'spring-core'", "spring-core", SearchScope.NAME},
                    {"ALL  dual-list      'report'", "report", SearchScope.ALL},
            };
            for (Object[] dim : dims) {
                List<Long> t = measure(svc, (String) dim[1], (SearchScope) dim[2]);
                report.append(String.format("%-40s p50=%.2f ms p95=%.2f ms (n=%d)%n",
                        dim[0], pct(t, 0.50), pct(t, 0.95), t.size()));
            }
            report.append(String.format("used heap after run: %d MB%n",
                    (rt.totalMemory() - rt.freeMemory()) / 1048576));
        } finally {
            svc.close();
        }

        report.append("==================================\n");
        System.out.println(report);
    }
}
