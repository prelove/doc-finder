package org.abitware.docfinder.watch;

import org.abitware.docfinder.index.IndexSettings;
import org.abitware.docfinder.index.LuceneIndexer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;

public class NetPollerService implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(NetPollerService.class);

    private final Path indexDir;
    private final IndexSettings settings;
    private final List<Path> roots;
    private final SnapshotStore store = new SnapshotStore();

    private ScheduledExecutorService scheduler;
    private final ExecutorService onDemand = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "docfinder-netpoll-once"); t.setDaemon(true); return t;
    });
    private volatile boolean running = false;

    public NetPollerService(Path indexDir, IndexSettings settings, List<Path> roots) {
        this.indexDir = indexDir;
        this.settings = settings;
        this.roots = new ArrayList<>(roots);
    }

    public static class PollStats {
        public long scannedFiles; public long created; public long modified; public long deleted; public long durationMs;
    }

    public void start(int minutes) {
        stop();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "docfinder-netpoller"); t.setDaemon(true); return t;
        });
        running = true;
        scheduler.scheduleWithFixedDelay(this::pollOnceSafe, 3, Math.max(1, minutes)*60L, TimeUnit.SECONDS);
    }

    public Future<PollStats> pollNowAsync() {
        return onDemand.submit(this::pollOnceWithStats);
    }

    public void pollNow() {
        pollOnceSafe();
    }

    private void pollOnceSafe() {
        if (!running) return;
        try { pollOnceWithStats(); } catch (Throwable ignore) { }
    }

    private PollStats pollOnceWithStats() throws IOException {
        long t0 = System.currentTimeMillis();
        PollStats stats = new PollStats();

        for (Path root : roots) {
            if (root == null || !Files.exists(root)) continue;

            Map<String, SnapshotStore.Entry> oldSnap = store.load(root);
            Map<String, SnapshotStore.Entry> newSnap = new HashMap<>();

            try {
                Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                    @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        try {
                            if (!attrs.isDirectory()) {
                                String abs = file.toAbsolutePath().toString();
                                newSnap.put(abs, new SnapshotStore.Entry(attrs.size(), attrs.lastModifiedTime().toMillis()));
                                stats.scannedFiles++;
                            }
                        } catch (Throwable t) {
                            log.warn("Snapshot visit file error: {}, exception: {}", file, t.getMessage());
                        }
                        return FileVisitResult.CONTINUE;
                    }
                    @Override public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        log.warn("Poll visit failed: {}, exception: {}", file, exc == null ? "null" : exc.getMessage());
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (Throwable t) {
                log.error("Walk file tree error in net poller for root: {}, exception: {}", root, t.getMessage());
            }

            Set<String> all = new HashSet<>();
            all.addAll(oldSnap.keySet());
            all.addAll(newSnap.keySet());

            try (LuceneIndexer idx = new LuceneIndexer(indexDir, settings)) {
                for (String p : all) {
                    SnapshotStore.Entry o = oldSnap.get(p);
                    SnapshotStore.Entry n = newSnap.get(p);
                    Path path = Paths.get(p);

                    if (o == null && n != null) {
                        safeUpsert(idx, path); stats.created++;
                    } else if (o != null && n == null) {
                        safeDelete(idx, path); stats.deleted++;
                    } else if (o != null && n != null && (o.size != n.size || o.mtime != n.mtime)) {
                        safeUpsert(idx, path); stats.modified++;
                    }
                }
                idx.commit();
            }

            store.save(root, newSnap);
        }

        stats.durationMs = System.currentTimeMillis() - t0;
        return stats;
    }

    private void safeUpsert(LuceneIndexer idx, Path p) {
        try { idx.upsertFile(p); } catch (Throwable ignore) {}
    }
    private void safeDelete(LuceneIndexer idx, Path p) {
        try { idx.deletePath(p); } catch (Throwable ignore) {}
    }

    public void stop() {
        running = false;
        if (scheduler != null) { scheduler.shutdownNow(); scheduler = null; }
        onDemand.shutdownNow();
    }

    @Override public void close() { stop(); }
}
