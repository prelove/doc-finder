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
        watcher = new LocalRecursiveWatcher(roots, this::onChange);
        watcher.start();
        flusher.scheduleWithFixedDelay(this::flushPendingSafe, FLUSH_INTERVAL_MS, FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void onChange(String type, Path path) {
        if (path == null) {
            return;
        }

        ChangeType incoming = toChangeType(type);
        synchronized (pendingLock) {
            ChangeType current = pendingChanges.get(path);
            ChangeType merged = merge(current, incoming);
            if (merged == null) {
                pendingChanges.remove(path);
            } else {
                pendingChanges.put(path, merged);
            }
        }
    }

    private void flushPendingSafe() {
        try {
            flushPending();
        } catch (Throwable t) {
            log.warn("Live watch flush failed: {}", t.getMessage());
        }
    }

    private void flushPending() {
        final Map<Path, ChangeType> batch = takeBatch(MAX_BATCH_SIZE);
        if (batch.isEmpty()) {
            return;
        }

        worker.submit(() -> IndexWriteCoordinator.run(() -> {
            boolean touched = false;
            try (LuceneIndexer indexer = new LuceneIndexer(indexDir, settings)) {
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
        }));
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
        }
        worker.shutdownNow();
    }
}
