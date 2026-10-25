package org.abitware.docfinder.search;

import org.abitware.docfinder.index.IndexSettings;
import org.abitware.docfinder.index.LuceneIndexer;
import org.abitware.docfinder.watch.LiveIndexService;
import org.abitware.docfinder.watch.NetPollerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Resource-stability evidence for the search/watch/poller rework: repeated
 * searches and repeated service start/stop cycles must not grow live heap or
 * the count of DocFinder-owned threads without bound. Measurements are
 * printed so regressions can be judged on trend, not just OOM absence.
 */
@Execution(ExecutionMode.SAME_THREAD)
class ResourceStabilityTest {

    @TempDir
    Path fixtureRoot;

    private Path source;
    private Path index;
    private IndexSettings settings;

    @BeforeEach
    void setUp() throws Exception {
        source = fixtureRoot.resolve("source");
        index = fixtureRoot.resolve("index");
        Files.createDirectories(source);
        settings = new IndexSettings();
    }

    @AfterEach
    void tearDown() {
        // nothing held open
    }

    private static long usedHeap() {
        System.gc();
        System.gc();
        return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    }

    /** Counts live threads owned by DocFinder (name-prefix based). */
    private static int docfinderThreads() {
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        int n = 0;
        for (long id : bean.getAllThreadIds()) {
            ThreadInfo info = bean.getThreadInfo(id);
            if (info == null) continue;
            String name = info.getThreadName();
            if (name != null && (name.startsWith("docfinder-") || name.startsWith("tika-extract"))) {
                n++;
            }
        }
        return n;
    }

    private static void write(Path p, String content) throws Exception {
        Files.write(p, content.getBytes("UTF-8"));
    }

    @Test
    void shouldKeepHeapAndThreadsStableAcrossRepeatedSearches() throws Exception {
        // Index enough live files that each search does real validation work.
        for (int i = 0; i < 30; i++) {
            write(source.resolve(String.format("stable-%02d.txt", i)), "lorem");
        }
        try (LuceneIndexer idx = new LuceneIndexer(index, settings)) {
            idx.indexFolder(source);
        }
        SourceRootProvider provider = () -> Collections.singletonList(source);

        int rounds = 40;
        LuceneSearchService service = new LuceneSearchService(index, settings, provider);
        try {
            // warmup — pools/caches reach steady state before measuring
            for (int i = 0; i < 5; i++) {
                service.searchDetailed(new SearchRequest("stable", 50, null, SearchScope.ALL, MatchMode.FUZZY));
            }

            long heapBefore = usedHeap();
            int threadsBefore = docfinderThreads();

            for (int i = 0; i < rounds; i++) {
                SearchOutcome o = service.searchDetailed(
                        new SearchRequest("stable", 50, null, SearchScope.ALL, MatchMode.FUZZY));
                assertFalse(o.results.isEmpty());
                // Alternate a second query so generation invalidation runs.
                service.searchDetailed(new SearchRequest("lorem", 10, null, SearchScope.ALL, MatchMode.FUZZY));
            }

            long heapAfter = usedHeap();
            int threadsAfter = docfinderThreads();

            long heapGrowth = heapAfter - heapBefore;
            int threadGrowth = threadsAfter - threadsBefore;
            System.out.printf("[ResourceStability] search rounds=%d heapBefore=%d heapAfter=%d growth=%d threadsBefore=%d threadsAfter=%d growth=%d%n",
                    rounds, heapBefore, heapAfter, heapGrowth, threadsBefore, threadsAfter, threadGrowth);

            assertTrue(heapGrowth < 64L * 1024 * 1024,
                    "repeated searches must not grow live heap unboundedly (growth=" + heapGrowth + ")");
            assertTrue(threadGrowth <= 4,
                    "repeated searches must not accumulate threads (growth=" + threadGrowth + ")");
        } finally {
            service.close();
        }
    }

    @Test
    void shouldReleaseThreadsAcrossServiceStartStopCycles() throws Exception {
        write(source.resolve("cycle.txt"), "lorem");
        try (LuceneIndexer idx = new LuceneIndexer(index, settings)) {
            idx.indexFolder(source);
        }
        SourceRootProvider provider = () -> Collections.singletonList(source);

        int threadsBefore = docfinderThreads();

        int cycles = 6;
        for (int i = 0; i < cycles; i++) {
            LuceneSearchService s = new LuceneSearchService(index, settings, provider);
            s.searchDetailed(new SearchRequest("cycle", 10, null, SearchScope.ALL, MatchMode.FUZZY));
            s.close();
        }
        for (int i = 0; i < cycles; i++) {
            NetPollerService p = new NetPollerService(index, settings, Collections.singletonList(source));
            p.start(60);
            p.stop();
        }
        for (int i = 0; i < cycles; i++) {
            LiveIndexService l = new LiveIndexService(index, settings, Collections.singletonList(source));
            l.start();
            l.close();
        }

        Thread.sleep(500); // let shutdownNow'd workers actually exit
        int threadsAfter = docfinderThreads();
        System.out.printf("[ResourceStability] cycles=%d threadsBefore=%d threadsAfter=%d growth=%d%n",
                cycles, threadsBefore, threadsAfter, threadsAfter - threadsBefore);

        assertTrue(threadsAfter - threadsBefore <= 4,
                "start/stop cycles must release threads (growth=" + (threadsAfter - threadsBefore) + ")");
    }

    @Test
    void shouldNotAccumulateExistenceThreadsAcrossRestartsUnderSlowIo() throws Exception {
        // RG-05 probe shape: stats that never answer promptly, service
        // reopened repeatedly. With a per-service pool this grew ~2 threads
        // per restart; the shared JVM pool must stay at its cap.
        write(source.resolve("slow.txt"), "lorem");
        try (LuceneIndexer idx = new LuceneIndexer(index, settings)) {
            idx.indexFolder(source);
        }
        SourceRootProvider provider = () -> Collections.singletonList(source);

        // Saturate the shared pool so every stat blocks like a hung share.
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        for (int i = 0; i < 4; i++) {
            LuceneSearchService.EXISTENCE_POOL.execute(() -> {
                try {
                    release.await(30, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        try {
            int existenceBefore = countThreads("docfinder-existence");
            for (int i = 0; i < 4; i++) {
                LuceneSearchService s = new LuceneSearchService(index, settings, provider);
                s.searchDetailed(new SearchRequest("slow", 10, null, SearchScope.ALL, MatchMode.FUZZY));
                s.close();
            }
            int existenceAfter = countThreads("docfinder-existence");
            System.out.printf("[ResourceStability] existenceBefore=%d existenceAfter=%d%n",
                    existenceBefore, existenceAfter);
            assertTrue(existenceAfter <= existenceBefore,
                    "closed services must not leave extra existence workers "
                            + "(before=" + existenceBefore + " after=" + existenceAfter + ")");
        } finally {
            release.countDown();
        }
    }

    private static int countThreads(String prefix) {
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        int n = 0;
        for (long id : bean.getAllThreadIds()) {
            ThreadInfo info = bean.getThreadInfo(id);
            if (info != null && info.getThreadName() != null && info.getThreadName().startsWith(prefix)) {
                n++;
            }
        }
        return n;
    }
}
