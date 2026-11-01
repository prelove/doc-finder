package org.abitware.docfinder.index;

import org.abitware.docfinder.util.IsolatedFileAccess;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Standalone acceptance harness; not a timing assertion in the regression suite. */
public final class IndexingFixRoundBenchmark {
    public static void main(String[] args) throws Exception {
        Path source = Path.of(args[0]);
        Path index = Path.of(args[1]);
        LuceneIndexer.Scheduling scheduling = LuceneIndexer.Scheduling.valueOf(args[2]);
        Path sample = source.resolve("batch-00/english.txt");
        // 所有模式先并发预热相同的 4 个 parser helper，避免冷启动差异冒充调度收益。
        try (var warm = Executors.newFixedThreadPool(8)) {
            List<Callable<Boolean>> tasks = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                tasks.add(() -> IsolatedFileAccess.extract(sample, 1000, 30000).isSuccessful());
            }
            for (int i = 0; i < 2; i++) {
                tasks.add(() -> IsolatedFileAccess.isTextLike(sample, 10000));
                tasks.add(() -> IsolatedFileAccess.attributes(sample, 10000) != null);
            }
            for (var result : warm.invokeAll(tasks)) {
                if (!result.get()) throw new IllegalStateException("helper warmup failed");
            }
        }
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        AtomicInteger degraded = new AtomicInteger();
        AtomicInteger timeouts = new AtomicInteger();
        LuceneIndexer.BatchOptions options = new LuceneIndexer.BatchOptions();
        options.scheduling = scheduling;
        options.permits = new java.util.concurrent.Semaphore(8, true) {
            @Override public void acquire() throws InterruptedException {
                super.acquire();
                peak.accumulateAndGet(8 - availablePermits(), Math::max);
            }
        };
        options.observer = new LuceneIndexer.BatchObserver() {
            @Override public void onTaskStarted(Path path) {
                if (scheduling == LuceneIndexer.Scheduling.SERIAL) {
                    peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                }
            }
            @Override public void onTaskFinished(Path path) {
                if (scheduling == LuceneIndexer.Scheduling.SERIAL) inFlight.decrementAndGet();
            }
        };
        options.extractor = (path, maxChars, timeoutMs) -> {
            try {
                var result = IsolatedFileAccess.extract(path, maxChars, timeoutMs);
                if (!result.isSuccessful()) degraded.incrementAndGet();
                return result;
            } catch (java.io.IOException e) {
                degraded.incrementAndGet();
                if (e.getCause() instanceof java.util.concurrent.TimeoutException) {
                    timeouts.incrementAndGet();
                }
                throw e;
            }
        };
        IndexSettings settings = new IndexSettings();
        settings.parseTimeoutSec = 30;
        LuceneIndexer.resetExtractionStateForTests();
        long started = System.nanoTime();
        int count = IndexWriteCoordinator.call(() -> {
            try (LuceneIndexer indexer = new LuceneIndexer(index, settings, options)) {
                return indexer.indexFolders(List.of(source), true);
            }
        });
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        Tombstones.close();
        String rss = Files.readAllLines(Path.of("/proc/self/status")).stream()
                .filter(line -> line.startsWith("VmHWM:")).findFirst().orElse("VmHWM: unavailable");
        if (inFlight.get() != 0) throw new AssertionError("unfinished tasks " + inFlight);
        System.out.printf("BENCH mode=%s elapsed_ms=%d docs=%d max_in_flight=%d degraded=%d timeouts=%d %s%n",
                scheduling, elapsedMs, count, peak.get(), degraded.get(), timeouts.get(), rss);
    }
}
