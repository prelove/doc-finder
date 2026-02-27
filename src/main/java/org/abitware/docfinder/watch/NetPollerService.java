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

    private ScheduledExecutorService scheduler;
    private ExecutorService onDemand;
    private volatile boolean running = false;

    public NetPollerService(Path indexDir, IndexSettings settings, List<Path> roots) {
        this.indexDir = indexDir;
        this.settings = settings;
        this.roots = new ArrayList<>(roots);
        this.onDemand = Executors.newSingleThreadExecutor(r -> {
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

    private static class RootScanResult {
        final Path root;
        final Map<String, SnapshotStore.Entry> oldSnap;
        final Map<String, SnapshotStore.Entry> newSnap;

        RootScanResult(Path root, Map<String, SnapshotStore.Entry> oldSnap, Map<String, SnapshotStore.Entry> newSnap) {
            this.root = root;
            this.oldSnap = oldSnap;
            this.newSnap = newSnap;
        }
    }

    public void start(int minutes) {
        stop();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "docfinder-netpoller");
            t.setDaemon(true);
            return t;
        });
        if (onDemand == null || onDemand.isShutdown()) {
            onDemand = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "docfinder-netpoll-once");
                t.setDaemon(true);
                return t;
            });
        }
        running = true;
        scheduler.scheduleWithFixedDelay(this::pollOnceSafe, 3, Math.max(1, minutes) * 60L, TimeUnit.SECONDS);
    }

    public Future<PollStats> pollNowAsync() {
        if (onDemand == null || onDemand.isShutdown()) {
            onDemand = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "docfinder-netpoll-once");
                t.setDaemon(true);
                return t;
            });
        }
        return onDemand.submit(this::pollOnceWithStats);
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

        List<Path> activeRoots = new ArrayList<>();
        for (Path root : roots) {
            if (root != null && Files.exists(root)) {
                activeRoots.add(root);
            }
        }

        List<RootScanResult> scanResults = scanRootsConcurrently(activeRoots, stats);

        try {
            IndexWriteCoordinator.run(() -> {
                try (LuceneIndexer idx = new LuceneIndexer(indexDir, settings)) {
                    for (RootScanResult scan : scanResults) {
                        applySnapshotDiff(idx, scan.oldSnap, scan.newSnap, stats);
                    }
                    idx.commit();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }

                for (RootScanResult scan : scanResults) {
                    store.save(scan.root, scan.newSnap);
                }
            });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException) {
                throw (IOException) e.getCause();
            }
            throw e;
        }

        stats.durationMs = System.currentTimeMillis() - t0;
        return stats;
    }

    private List<RootScanResult> scanRootsConcurrently(List<Path> activeRoots, PollStats stats) {
        List<RootScanResult> results = new ArrayList<>();
        if (activeRoots.isEmpty()) {
            return results;
        }

        int threads = Math.min(MAX_SCAN_THREADS, Math.max(1, activeRoots.size()));
        ExecutorService scanPool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "docfinder-netpoll-scan");
            t.setDaemon(true);
            return t;
        });

        try {
            List<Callable<RootScanResult>> tasks = new ArrayList<>();
            for (Path root : activeRoots) {
                tasks.add(() -> scanOneRoot(root, stats));
            }
            List<Future<RootScanResult>> futures = scanPool.invokeAll(tasks);
            for (Future<RootScanResult> future : futures) {
                try {
                    RootScanResult r = future.get();
                    if (r != null) {
                        results.add(r);
                    }
                } catch (Exception e) {
                    log.warn("Scan future failed: {}", e.getMessage());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            scanPool.shutdownNow();
        }

        return results;
    }

    private RootScanResult scanOneRoot(Path root, PollStats stats) {
        Map<String, SnapshotStore.Entry> oldSnap = store.load(root);
        Map<String, SnapshotStore.Entry> newSnap = new HashMap<>();

        try {
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    try {
                        if (!attrs.isDirectory()) {
                            String abs = file.toAbsolutePath().toString();
                            newSnap.put(abs, new SnapshotStore.Entry(attrs.size(), attrs.lastModifiedTime().toMillis()));
                            synchronized (stats) {
                                stats.scannedFiles++;
                            }
                        }
                    } catch (Throwable t) {
                        log.warn("Snapshot visit file error: {}, exception: {}", file, t.getMessage());
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    log.warn("Poll visit failed: {}, exception: {}", file, exc == null ? "null" : exc.getMessage());
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (Throwable t) {
            log.error("Walk file tree error in net poller for root: {}, exception: {}", root, t.getMessage());
        }

        return new RootScanResult(root, oldSnap, newSnap);
    }

    private void applySnapshotDiff(LuceneIndexer idx,
                                   Map<String, SnapshotStore.Entry> oldSnap,
                                   Map<String, SnapshotStore.Entry> newSnap,
                                   PollStats stats) {
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
    }

    @Override
    public void close() {
        stop();
    }
}
