package org.abitware.docfinder.watch;

import org.abitware.docfinder.index.ConfigManager;
import org.abitware.docfinder.index.IndexSettings;
import org.abitware.docfinder.index.LuceneIndexer;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public class LiveIndexService implements AutoCloseable {
    private final Path indexDir;
    private final IndexSettings settings;
    private final List<Path> roots;
    private LocalRecursiveWatcher watcher;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "docfinder-liveindex-worker"); t.setDaemon(true); return t;
    });

    // Added field for LuceneIndexer
    private final LuceneIndexer luceneIndexer;

    public LiveIndexService(Path indexDir, IndexSettings settings, List<Path> roots) throws IOException { // Constructor now throws IOException
        this.indexDir = indexDir;
        this.settings = settings;
        this.roots = new ArrayList<>(roots);
        this.luceneIndexer = new LuceneIndexer(indexDir, settings); // Initialize LuceneIndexer here
    }

    public void start() throws Exception {
        if (watcher != null) return;
        watcher = new LocalRecursiveWatcher(roots, this::onChange);
        watcher.start();
    }

    private void onChange(String type, Path path) {
        // 仅处理文件（目录创建我们已用于注册递归）
        worker.submit(() -> {
            try {
                // LuceneIndexer idx = new LuceneIndexer(indexDir, settings); // REMOVED
                if ("DELETE".equals(type)) {
                    luceneIndexer.deletePath(path);
                } else {
                    // 等待文件“稳定”：最多 2 秒，尺寸两次一致
                    if (waitStable(path, 2000)) {
                        luceneIndexer.upsertFile(path);
                    }
                }
                luceneIndexer.commit(); // Commit changes after each operation
            } catch (Throwable ex) { // Changed from ignore
                // TODO: log exception details when logging framework available
                ex.printStackTrace(); // Print stack trace for now
            }
        });
    }

    /** 简单稳定检测：重复 stat 两次间隔 300ms，尺寸一致即认为稳定 */
    private boolean waitStable(Path p, long maxMs) {
        long deadline = System.currentTimeMillis() + maxMs;
        try {
            long last = -1;
            while (System.currentTimeMillis() < deadline) {
                if (!Files.exists(p) || Files.isDirectory(p)) return false;
                long size = Files.size(p);
                if (size == last) return true;
                last = size;
                Thread.sleep(300);
            }
        } catch (Exception ignore) {} // TODO: log this exception
        return true; // 超时直接尝试解析
    }

    @Override public void close() {
        try { if (watcher != null) watcher.close(); } catch (Exception ignore) {} // TODO: log this exception
        watcher = null;
        worker.shutdownNow();
        try {
            if (luceneIndexer != null) {
                luceneIndexer.close(); // Close the LuceneIndexer
            }
        } catch (IOException e) {
            // TODO: log this error
            e.printStackTrace();
        }
    }
}