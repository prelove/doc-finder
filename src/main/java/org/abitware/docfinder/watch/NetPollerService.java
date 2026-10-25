package org.abitware.docfinder.watch;

import org.abitware.docfinder.index.IndexSettings;
import org.abitware.docfinder.index.IndexWriteCoordinator;
import org.abitware.docfinder.index.LuceneIndexer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class NetPollerService implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(NetPollerService.class);
    private static final int MAX_SCAN_THREADS = 4;

    private final Path indexDir;
    private final IndexSettings settings;
    private final List<Path> roots;
    private final SnapshotStore store = new SnapshotStore();
    private final Runnable onAfterCommit;

    private ScheduledExecutorService scheduler;
    private ExecutorService onDemand;
    private volatile boolean running = false;
    /**
     * Single-flight guard: an automatic tick and a manual poll can never
     * overlap — and since one poll covers all roots, per-source overlap is
     * covered too. A second attempt returns immediately with empty stats.
     */
    private final java.util.concurrent.atomic.AtomicBoolean pollInFlight =
            new java.util.concurrent.atomic.AtomicBoolean();
    /**
     * Persistent bounded scan pool (created once, reused across rounds —
     * no per-round fixed pool churn): at most MAX_SCAN_THREADS snapshot
     * pairs are in memory at once and idle workers expire.
     */
    private java.util.concurrent.ThreadPoolExecutor scanPool;

    private static java.util.concurrent.ThreadPoolExecutor newScanPool() {
        java.util.concurrent.ThreadPoolExecutor p = new java.util.concurrent.ThreadPoolExecutor(
                MAX_SCAN_THREADS, MAX_SCAN_THREADS,
                60L, TimeUnit.SECONDS,
                new java.util.concurrent.ArrayBlockingQueue<Runnable>(64),
                r -> {
                    Thread t = new Thread(r, "docfinder-netpoll-scan");
                    t.setDaemon(true);
                    return t;
                });
        p.allowCoreThreadTimeOut(true);
        return p;
    }

    public NetPollerService(Path indexDir, IndexSettings settings, List<Path> roots) {
        this(indexDir, settings, roots, null);
    }

    public NetPollerService(Path indexDir, IndexSettings settings, List<Path> roots, Runnable onAfterCommit) {
        this.indexDir = indexDir;
        this.settings = settings;
        this.roots = new ArrayList<>(roots);
        this.onAfterCommit = onAfterCommit;
        this.onDemand = newOnDemand();
        this.scanPool = newScanPool();
    }

    /** Manual-poll executor: single worker, bounded queue — never piles up. */
    private static ExecutorService newOnDemand() {
        return new java.util.concurrent.ThreadPoolExecutor(1, 1,
                0L, TimeUnit.MILLISECONDS,
                new java.util.concurrent.ArrayBlockingQueue<Runnable>(8),
                r -> {
                    Thread t = new Thread(r, "docfinder-netpoll-once");
                    t.setDaemon(true);
                    return t;
                });
    }

    public static class PollStats {
        public long scannedFiles;
        public long created;
        public long modified;
        public long deleted;
        public long durationMs;
    }

    static class RootStats {
        long scannedFiles;
        long created;
        long modified;
        long deleted;
        boolean complete;
    }

    public void start(int minutes) {
        stop();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "docfinder-netpoller");
            t.setDaemon(true);
            return t;
        });
        if (scanPool == null || scanPool.isShutdown()) {
            scanPool = newScanPool();
        }
        if (onDemand == null || onDemand.isShutdown()) {
            onDemand = newOnDemand();
        }
        running = true;
        scheduler.scheduleWithFixedDelay(this::pollOnceSafe, 3, Math.max(1, minutes) * 60L, TimeUnit.SECONDS);
    }

    public Future<PollStats> pollNowAsync() {
        if (onDemand == null || onDemand.isShutdown()) {
            onDemand = newOnDemand();
        }
        if (pollInFlight.get()) {
            // A poll is already running — answer immediately instead of
            // queuing behind it on the single on-demand thread.
            return java.util.concurrent.CompletableFuture.completedFuture(new PollStats());
        }
        try {
            return onDemand.submit(this::pollOnceWithStats);
        } catch (java.util.concurrent.RejectedExecutionException rex) {
            return java.util.concurrent.CompletableFuture.completedFuture(new PollStats());
        }
    }

    public void pollNow() {
        pollOnceSafe();
    }

    private void pollOnceSafe() {
        if (!running) {
            return;
        }
        try {
            pollOnceWithStats();
        } catch (Throwable ignore) {
        }
    }

    private PollStats pollOnceWithStats() throws IOException {
        long t0 = System.currentTimeMillis();
        PollStats stats = new PollStats();
        if (!pollInFlight.compareAndSet(false, true)) {
            return stats; // another poll (scheduled or manual) is already running
        }
        try {
            List<Path> activeRoots = new ArrayList<>();
            for (Path root : roots) {
                if (root != null && Files.exists(root)) {
                    activeRoots.add(root);
                }
            }

            // Per-root pipeline on the bounded pool: each task scans, diffs,
            // commits and saves ONE root, then releases both snapshot maps —
            // at most MAX_SCAN_THREADS snapshot pairs are held at once.
            List<Future<RootStats>> futures = new ArrayList<>();
            for (Path root : activeRoots) {
                futures.add(scanPool.submit(() -> processRoot(root)));
            }
            for (Future<RootStats> f : futures) {
                try {
                    RootStats rs = f.get();
                    if (rs != null) {
                        stats.scannedFiles += rs.scannedFiles;
                        stats.created += rs.created;
                        stats.modified += rs.modified;
                        stats.deleted += rs.deleted;
                    }
                } catch (InterruptedException ie) {
                    // Service stopping — preserve the interrupt and skip the
                    // remaining merges; the in-flight root task exits on its own.
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.warn("Poll root task failed", e);
                }
            }
        } finally {
            pollInFlight.set(false);
        }

        // Notify search service to refresh reader after commit, making changes from this poll immediately visible to next search
        if (onAfterCommit != null) {
            onAfterCommit.run();
        }

        stats.durationMs = System.currentTimeMillis() - t0;
        return stats;
    }

    /** Per-root scan + diff + baseline persist, all inside one bounded task. */
    private RootStats processRoot(Path root) {
        RootStats rs = new RootStats();
        Map<String, SnapshotStore.Entry> oldSnap = store.load(root);
        if (oldSnap == null) {
            log.warn("Baseline snapshot unreadable for {} — skipping diff this round", root);
            return rs;
        }

        Map<String, SnapshotStore.Entry> newSnap = new HashMap<>();
        boolean[] complete = { true };
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    try {
                        if (!attrs.isDirectory()) {
                            String abs = file.toAbsolutePath().toString();
                            newSnap.put(abs, new SnapshotStore.Entry(attrs.size(), attrs.lastModifiedTime().toMillis()));
                            rs.scannedFiles++;
                        }
                    } catch (Throwable t) {
                        complete[0] = false;
                        log.warn("Snapshot visit file error: {}, exception: {}", file, t.getMessage());
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    // An unreadable entry means the snapshot is partial: it
                    // may neither replace the baseline nor justify deletions.
                    complete[0] = false;
                    log.warn("Poll visit failed: {}, exception: {}", file, exc == null ? "null" : exc.getMessage());
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (Throwable t) {
            complete[0] = false;
            log.error("Walk file tree error in net poller for root: {}, exception: {}", root, t.getMessage());
        }
        rs.complete = complete[0];

        if (!running) {
            return rs; // service stopping — don't write against a closed index
        }

        try {
            IndexWriteCoordinator.run(() -> {
                try (LuceneIndexer idx = new LuceneIndexer(indexDir, settings)) {
                    applySnapshotDiff(idx, oldSnap, newSnap, rs.complete, rs);
                    idx.commit();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (RuntimeException e) {
            log.warn("Poll diff apply failed for {}: {}", root, e.getMessage());
            return rs;
        }

        // Only a fully scanned snapshot may become the new baseline — a
        // partial scan is never persisted as "complete" nor used to delete.
        if (rs.complete && !store.save(root, newSnap)) {
            log.warn("Baseline snapshot save failed for {} — old baseline kept", root);
        }
        return rs;
    }

    void applySnapshotDiff(LuceneIndexer idx,
                                   Map<String, SnapshotStore.Entry> oldSnap,
                                   Map<String, SnapshotStore.Entry> newSnap,
                                   boolean completeScan,
                                   RootStats stats) {
        Set<String> all = new HashSet<>();
        all.addAll(oldSnap.keySet());
        all.addAll(newSnap.keySet());

        for (String p : all) {
            SnapshotStore.Entry o = oldSnap.get(p);
            SnapshotStore.Entry n = newSnap.get(p);

            Path path;
            try {
                path = Paths.get(p);
            } catch (Exception ex) {
                continue;
            }

            if (o == null && n != null) {
                safeUpsert(idx, path);
                stats.created++;
            } else if (o != null && n == null) {
                if (!completeScan) {
                    // Partial scan: an absent path may simply be inside an
                    // unreadable branch — never delete on unproven absence.
                    continue;
                }
                safeDelete(idx, path);
                stats.deleted++;
            } else if (o != null && n != null && (o.size != n.size || o.mtime != n.mtime)) {
                safeUpsert(idx, path);
                stats.modified++;
            }
        }
    }

    private void safeUpsert(LuceneIndexer idx, Path p) {
        try {
            idx.upsertFile(p);
        } catch (Throwable ignore) {
        }
    }

    private void safeDelete(LuceneIndexer idx, Path p) {
        try {
            idx.deletePath(p);
        } catch (Throwable ignore) {
        }
    }

    public void stop() {
        running = false;
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        if (onDemand != null) {
            onDemand.shutdownNow();
            onDemand = null;
        }
        if (scanPool != null) {
            scanPool.shutdownNow();
            scanPool = null;
        }
    }

    @Override
    public void close() {
        stop();
    }
}
