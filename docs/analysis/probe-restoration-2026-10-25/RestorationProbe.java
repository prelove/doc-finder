import org.abitware.docfinder.index.IndexSettings;
import org.abitware.docfinder.index.IndexWriteCoordinator;
import org.abitware.docfinder.index.LuceneIndexer;
import org.abitware.docfinder.index.Tombstones;
import org.abitware.docfinder.search.LuceneSearchService;
import org.abitware.docfinder.search.MatchMode;
import org.abitware.docfinder.search.SearchOutcome;
import org.abitware.docfinder.search.SearchRequest;
import org.abitware.docfinder.search.SearchScope;
import org.abitware.docfinder.search.SourceRootProvider;
import org.abitware.docfinder.util.Utils;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.FSDirectory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Direct-JAR acceptance probe for the four scenarios that failed
 * independent revalidation (see docs/analysis/2026-10-25-resource-goal-review.md):
 *
 *   P1  idle restoration      — file restored while the app sits idle must
 *                               re-enter the index with ZERO searches fired.
 *   P2  restart + global only — tombstone persisted, file restored while
 *                               down, fresh service, only ALL-scope searches.
 *   P3  tombstone tail        — >256 still-missing head entries must not
 *                               starve a restored file at the tail.
 *   P4  same-IP independence  — two concurrent null-tag requests (what the
 *                               web handler now sends) must not cancel.
 *   P5  stale purge vs upsert — a doc re-indexed after classification must
 *                               survive the queued purge.
 *
 * Run from a dedicated working directory (user.dir/.docfinder stays local).
 * Exits non-zero when any scenario fails.
 */
public class RestorationProbe {

    private static final IndexSettings SETTINGS = new IndexSettings();
    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) throws Exception {
        // Scenario trees MUST live outside user.dir: LuceneIndexer treats
        // anything under the launch directory as app-internal and excludes
        // it. Runtime data (.docfinder) still lands inside the run dir.
        Path work = Paths.get(args.length > 0 ? args[0] : "../probe-trees")
                .toAbsolutePath().normalize();
        Files.createDirectories(work);
        System.out.println("[probe] work=" + work
                + " user.dir=" + System.getProperty("user.dir"));

        p1IdleRestoration(work.resolve("p1"));
        p2RestartGlobalOnly(work.resolve("p2"));
        p3TombstoneTail(work.resolve("p3"));
        p4SameIpIndependence(work.resolve("p4"));
        p5StalePurgeVsUpsert(work.resolve("p5"));

        System.out.println("[probe] RESULT pass=" + pass + " fail=" + fail);
        System.exit(fail == 0 ? 0 : 1);
    }

    // ---------------- scenarios ----------------

    /** P1: restore while idle; no searches at all after the restore. */
    static void p1IdleRestoration(Path dir) throws Exception {
        Path src = dir.resolve("source");
        Path idx = dir.resolve("index");
        Files.createDirectories(src);
        write(src.resolve("p1-file.txt"));

        indexFolder(src, idx);
        LuceneSearchService svc = open(src, idx);
        try {
            Files.delete(src.resolve("p1-file.txt"));
            check("P1 pre: ghost hidden",
                    search(svc, "p1-file").results.isEmpty());
            Thread.sleep(700); // let the async purge land
            check("P1 pre: doc really purged", !hasDoc(idx, src, "p1-file.txt"));

            write(src.resolve("p1-file.txt")); // restore — then TOTAL silence
            boolean found = pollHasDoc(idx, src, "p1-file.txt", 8_000);
            check("P1 idle sweep resurrects without any search", found);
        } finally {
            svc.close();
        }
    }

    /** P2: fresh service (restart), only ALL-scope searches afterwards. */
    static void p2RestartGlobalOnly(Path dir) throws Exception {
        Path src = dir.resolve("source");
        Path idx = dir.resolve("index");
        Files.createDirectories(src);
        write(src.resolve("p2-file.txt"));

        indexFolder(src, idx);
        LuceneSearchService svc = open(src, idx);
        Files.delete(src.resolve("p2-file.txt"));
        check("P2 pre: ghost hidden",
                search(svc, "p2-file").results.isEmpty());
        Thread.sleep(700);
        svc.close(); // tombstone persisted to disk

        write(src.resolve("p2-file.txt")); // restored while "app" was down
        svc = open(src, idx);            // "restarted"
        try {
            boolean found = false;
            for (int i = 0; i < 12 && !found; i++) { // ~6s of global searches
                SearchOutcome o = search(svc, "p2-file"); // ALL scope only
                found = o.results.stream()
                        .anyMatch(r -> "p2-file.txt".equals(r.name));
                if (!found) Thread.sleep(500);
            }
            check("P2 restart + global searches resurrect", found);
        } finally {
            svc.close();
        }
    }

    /** P3: 300 still-missing head tombstones must not starve the tail. */
    static void p3TombstoneTail(Path dir) throws Exception {
        Path src = dir.resolve("source");
        Path idx = dir.resolve("index");
        Files.createDirectories(src);
        indexFolder(src, idx); // index may be empty; fine
        LuceneSearchService svc = open(src, idx);
        List<String> fakes = new ArrayList<>();
        try {
            for (int i = 0; i < 300; i++) {
                String p = Utils.normalizeForIndex(
                        src.resolve(String.format("missing-%03d.txt", i)));
                fakes.add(p);
                Tombstones.record(p);
            }
            Path real = src.resolve("tail-restored.txt");
            write(real);
            Tombstones.record(Utils.normalizeForIndex(real));

            boolean found = pollHasDoc(idx, src, "tail-restored.txt", 12_000);
            check("P3 rotating cursor reaches tail past 300 missing", found);
        } finally {
            for (String p : fakes) Tombstones.remove(p);
            Tombstones.remove(Utils.normalizeForIndex(
                    src.resolve("tail-restored.txt")));
            svc.close();
        }
    }

    /** P4: concurrent null-tag (web-style) requests never cancel each other. */
    static void p4SameIpIndependence(Path dir) throws Exception {
        Path src = dir.resolve("source");
        Path idx = dir.resolve("index");
        Files.createDirectories(src);
        for (int i = 0; i < 20; i++) write(src.resolve("p4-doc-" + i + ".txt"));
        indexFolder(src, idx);
        LuceneSearchService svc = open(src, idx);
        try {
            ExecutorService pool = Executors.newFixedThreadPool(2);
            Callable<SearchOutcome> call = () -> search(svc, "p4-doc");
            Future<SearchOutcome> a = pool.submit(call);
            Future<SearchOutcome> b = pool.submit(call);
            SearchOutcome ra = a.get(20, TimeUnit.SECONDS);
            SearchOutcome rb = b.get(20, TimeUnit.SECONDS);
            pool.shutdownNow();
            check("P4 both concurrent requests complete fully",
                    ra.results.size() == 20 && rb.results.size() == 20);
        } finally {
            svc.close();
        }
    }

    /** P5: doc re-indexed after purge classification must survive. */
    static void p5StalePurgeVsUpsert(Path dir) throws Exception {
        Path src = dir.resolve("source");
        Path idx = dir.resolve("index");
        Files.createDirectories(src);
        write(src.resolve("p5-file.txt"));
        indexFolder(src, idx);
        LuceneSearchService svc = open(src, idx);
        try {
            Files.delete(src.resolve("p5-file.txt"));

            IndexWriteCoordinator.run(() -> {
                SearchOutcome o = search(svc, "p5-file"); // ghost verdict queued
                if (!o.results.isEmpty()) {
                    throw new IllegalStateException("ghost was not detected");
                }
                try {
                    write(src.resolve("p5-file.txt")); // restore
                    try (LuceneIndexer w = new LuceneIndexer(idx, SETTINGS)) {
                        w.upsertFile(src.resolve("p5-file.txt")); // newer gen
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            Thread.sleep(700); // purge lands
            check("P5 newer upsert survives stale purge verdict",
                    hasDoc(idx, src, "p5-file.txt"));
        } finally {
            svc.close();
        }
    }

    // ---------------- helpers ----------------

    static void write(Path p) throws Exception {
        Files.write(p, ("body " + p.getFileName()).getBytes("UTF-8"));
    }

    static void indexFolder(Path src, Path idx) throws Exception {
        try (LuceneIndexer w = new LuceneIndexer(idx, SETTINGS)) {
            w.indexFolder(src);
        }
    }

    static LuceneSearchService open(Path src, Path idx) throws Exception {
        SourceRootProvider provider = () -> Collections.singletonList(src);
        return new LuceneSearchService(idx, SETTINGS, provider);
    }

    static SearchOutcome search(LuceneSearchService svc, String q) {
        return svc.searchDetailed(new SearchRequest(
                q, 50, null, SearchScope.ALL, MatchMode.FUZZY));
    }

    static boolean hasDoc(Path idx, Path src, String name) throws Exception {
        String want = Utils.normalizeForIndex(src.resolve(name));
        try (FSDirectory d = FSDirectory.open(idx);
             DirectoryReader r = DirectoryReader.open(d)) {
            return new IndexSearcher(r).search(
                    new TermQuery(new Term("path", want)), 1).totalHits.value > 0;
        }
    }

    static boolean pollHasDoc(Path idx, Path src, String name, long budgetMs)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs);
        while (System.nanoTime() < deadline) {
            if (hasDoc(idx, src, name)) return true;
            Thread.sleep(500);
        }
        return hasDoc(idx, src, name);
    }

    static void check(String label, boolean ok) {
        System.out.println("[probe] " + (ok ? "PASS " : "FAIL ") + label);
        if (ok) pass++; else fail++;
    }
}
