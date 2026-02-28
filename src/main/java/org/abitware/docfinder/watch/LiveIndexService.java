package org.abitware.docfinder.watch;

import org.abitware.docfinder.index.IndexSettings;
import org.abitware.docfinder.index.IndexWriteCoordinator;
import org.abitware.docfinder.index.LuceneIndexer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LiveIndexService implements AutoCloseable {
    private final Path indexDir;
    private final IndexSettings settings;
    private final List<Path> roots;
    private final Runnable onAfterCommit;
    private LocalRecursiveWatcher watcher;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "docfinder-liveindex-worker");
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
    }

    private void onChange(String type, Path path) {
        worker.submit(() -> IndexWriteCoordinator.run(() -> {
            try (LuceneIndexer indexer = new LuceneIndexer(indexDir, settings)) {
                if ("DELETE".equals(type)) {
                    indexer.deletePath(path);
                } else if (waitStable(path, 2000)) {
                    indexer.upsertFile(path);
                }
                indexer.commit();
                // Notify search service to refresh reader after commit, making changes immediately visible to next search
                if (onAfterCommit != null) {
                    onAfterCommit.run();
                }
            } catch (Throwable ex) {
                ex.printStackTrace();
            }
        }));
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
        worker.shutdownNow();
    }
}
