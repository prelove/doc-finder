package org.abitware.docfinder.watch;

import org.abitware.docfinder.index.IndexSettings;
import org.abitware.docfinder.index.IndexWriteCoordinator;
import org.abitware.docfinder.index.LuceneIndexer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class LiveIndexService implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(LiveIndexService.class);

    private static final int MAX_BATCH_SIZE = 500;
    private static final long FLUSH_INTERVAL_MS = 700L;
    /**
     * Bounded pending-change map: an event flood beyond this no longer grows
     * memory — the owning source root is marked reconciliation-needed and a
     * full rescan covers the gap instead of silently dropping changes.
     */
    private static final int MAX_PENDING_CHANGES = 50_000;

    private enum ChangeType {
        CREATE,
        MODIFY,
        DELETE
    }

    private final Path indexDir;
    private final IndexSettings settings;
    private final List<Path> roots;
    private final Runnable onAfterCommit;

    private final Object pendingLock = new Object();
    private final LinkedHashMap<Path, ChangeType> pendingChanges = new LinkedHashMap<>();
    /** Source roots that lost events (map overflow or watch OVERFLOW) and
     *  need a full rescan. Bounded by the number of configured roots. */
    private final java.util.Set<Path> reconcileRoots = new java.util.LinkedHashSet<>();
    /** Single-flight drain: at most one worker task is ever queued/running. */
    private final java.util.concurrent.atomic.AtomicBoolean drainActive =
            new java.util.concurrent.atomic.AtomicBoolean();
    private volatile boolean closed;

    private LocalRecursiveWatcher watcher;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "docfinder-liveindex-worker");
        t.setDaemon(true);
        return t;
    });
    private final ScheduledExecutorService flusher = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "docfinder-liveindex-flush");
        t.setDaemon(true);
        return t;
    });

    public LiveIndexService(Path indexDir, IndexSettings settings, List<Path> roots) {
        this(indexDir, settings, roots, null);
    }

    public LiveIndexService(Path indexDir, IndexSettings settings, List<Path> roots, Runnable onAfterCommit) {
        this.indexDir = indexDir;
        this.settings = settings;
        this.roots = new ArrayList<>(roots);
        this.onAfterCommit = onAfterCommit;
    }

    public void start() throws Exception {
        if (watcher != null) {
            return;
        }
        watcher = new LocalRecursiveWatcher(roots, new LocalRecursiveWatcher.Listener() {
            @Override
            public void onChange(String type, Path path) {
                LiveIndexService.this.onChange(type, path);
            }

            @Override
            public void onOverflow(Path dir) {
                LiveIndexService.this.onOverflow(dir);
            }
        });
        watcher.start();
        flusher.scheduleWithFixedDelay(this::flushPendingSafe, FLUSH_INTERVAL_MS, FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    void onChange(String type, Path path) {
        if (path == null) {
            return;
        }

        ChangeType incoming = toChangeType(type);
        synchronized (pendingLock) {
            ChangeType current = pendingChanges.get(path);
            ChangeType merged = merge(current, incoming);
            if (merged == null) {
                pendingChanges.remove(path);
            } else if (current == null && pendingChanges.size() >= MAX_PENDING_CHANGES) {
                // Backlog saturated: mark the owning root for a full rescan —
                // the change is never silently dropped.
                markReconcileLocked(path);
            } else {
                pendingChanges.put(path, merged);
            }
        }
    }

    void onOverflow(Path dir) {
        if (dir == null) {
            return;
        }
        synchronized (pendingLock) {
            markReconcileLocked(dir);
        }
    }

    /** Must be called holding pendingLock. */
    private void markReconcileLocked(Path p) {
        for (Path root : roots) {
            if (root != null && p.startsWith(root)) {
                if (reconcileRoots.add(root)) {
                    log.warn("Watch backlog overflow: source {} marked reconciliation-needed", root);
                }
                return;
            }
        }
        log.warn("Watch overflow for path outside all roots: {}", p);
    }

    /** Roots currently marked reconciliation-needed (test/inspection view). */
    java.util.Set<Path> pendingReconcileRoots() {
        synchronized (pendingLock) {
            return new java.util.LinkedHashSet<>(reconcileRoots);
        }
    }

    private void flushPendingSafe() {
        try {
            flushPending();
        } catch (Throwable t) {
            log.warn("Live watch flush failed: {}", t.getMessage());
        }
    }

    /**
     * Single-flight drain: the flusher only submits when no drain is queued
     * or running, so the single worker's queue can never pile up batches.
     */
    private void flushPending() {
        if (closed || !drainActive.compareAndSet(false, true)) {
            return;
        }
        try {
            worker.submit(this::drainPending);
        } catch (RuntimeException rejected) {
            drainActive.set(false);
        }
    }

    private void drainPending() {
        try {
            while (true) {
                final Path reconcile = pollReconcileRoot();
                final Map<Path, ChangeType> batch = takeBatch(MAX_BATCH_SIZE);
                if (reconcile == null && batch.isEmpty()) {
                    return;
                }
                IndexWriteCoordinator.run(() -> applyBatch(reconcile, batch));
            }
        } finally {
            drainActive.set(false);
            // A change that arrived between the last take and the flag reset
            // must trigger one more drain — checked under the same lock used
            // by producers, so no work can be lost in the race.
            synchronized (pendingLock) {
                if (!closed && (!pendingChanges.isEmpty() || !reconcileRoots.isEmpty())) {
                    flushPending();
                }
            }
        }
    }

    private void applyBatch(Path reconcile, Map<Path, ChangeType> batch) {
        boolean touched = false;
        try (LuceneIndexer indexer = new LuceneIndexer(indexDir, settings)) {
            if (reconcile != null) {
                // Events were lost for this source: a full rescan upserts
                // everything that exists now; entries whose files vanished
                // converge via search-time ghost filtering/purge.
                indexer.indexFolder(reconcile);
                touched = true;
            }
            for (Map.Entry<Path, ChangeType> e : batch.entrySet()) {
                Path path = e.getKey();
                ChangeType changeType = e.getValue();
                if (changeType == ChangeType.DELETE) {
                    indexer.deletePath(path);
                    touched = true;
                } else if (waitStable(path, 2000)) {
                    indexer.upsertFile(path);
                    touched = true;
                }
            }

            if (touched) {
                indexer.commit();
                if (onAfterCommit != null) {
                    onAfterCommit.run();
                }
            }
        } catch (Throwable ex) {
            log.warn("Live watch batch apply failed: {}", ex.getMessage());
        }
    }

    private Path pollReconcileRoot() {
        synchronized (pendingLock) {
            java.util.Iterator<Path> it = reconcileRoots.iterator();
            if (!it.hasNext()) {
                return null;
            }
            Path root = it.next();
            it.remove();
            return root;
        }
    }

    private Map<Path, ChangeType> takeBatch(int maxBatchSize) {
        synchronized (pendingLock) {
            if (pendingChanges.isEmpty()) {
                return Collections.emptyMap();
            }
            LinkedHashMap<Path, ChangeType> batch = new LinkedHashMap<>();
            int count = 0;
            for (Map.Entry<Path, ChangeType> e : pendingChanges.entrySet()) {
                batch.put(e.getKey(), e.getValue());
                count++;
                if (count >= maxBatchSize) {
                    break;
                }
            }
            for (Path p : batch.keySet()) {
                pendingChanges.remove(p);
            }
            return batch;
        }
    }

    private static ChangeType merge(ChangeType current, ChangeType incoming) {
        if (incoming == null) {
            return current;
        }
        if (current == null) {
            return incoming;
        }
        if (current == ChangeType.CREATE) {
            if (incoming == ChangeType.DELETE) {
                return null;
            }
            return ChangeType.CREATE;
        }
        if (current == ChangeType.MODIFY) {
            return incoming == ChangeType.DELETE ? ChangeType.DELETE : ChangeType.MODIFY;
        }
        // current == DELETE
        return incoming == ChangeType.DELETE ? ChangeType.DELETE : ChangeType.MODIFY;
    }

    private ChangeType toChangeType(String type) {
        if ("DELETE".equals(type)) {
            return ChangeType.DELETE;
        }
        if ("CREATE".equals(type)) {
            return ChangeType.CREATE;
        }
        return ChangeType.MODIFY;
    }

    /** 简单稳定检测：重复 stat 两次间隔 300ms，尺寸一致即认为稳定 */
    private boolean waitStable(Path p, long maxMs) {
        long deadline = System.currentTimeMillis() + maxMs;
        try {
            long last = -1;
            while (System.currentTimeMillis() < deadline) {
                if (!Files.exists(p) || Files.isDirectory(p)) {
                    return false;
                }
                long size = Files.size(p);
                if (size == last) {
                    return true;
                }
                last = size;
                Thread.sleep(300);
            }
        } catch (Exception ignore) {
        }
        return true;
    }

    @Override
    public void close() {
        closed = true;
        try {
            if (watcher != null) {
                watcher.close();
            }
        } catch (Exception ignore) {
        }
        watcher = null;
        try {
            flusher.shutdownNow();
        } catch (Exception ignore) {
        }
        synchronized (pendingLock) {
            pendingChanges.clear();
            reconcileRoots.clear();
        }
        worker.shutdownNow();
    }
}
