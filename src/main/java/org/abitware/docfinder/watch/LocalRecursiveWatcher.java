package org.abitware.docfinder.watch;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;
import static java.nio.file.StandardWatchEventKinds.*;

public class LocalRecursiveWatcher implements AutoCloseable {
    public interface Listener {
        // type: "CREATE" | "MODIFY" | "DELETE"
        void onChange(String type, Path path);
    }

    private final List<Path> roots;
    private final Listener listener;
    private final WatchService ws;
    private final Map<WatchKey, Path> key2dir = new ConcurrentHashMap<>();
    private final ExecutorService loop = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "docfinder-watch-loop"); t.setDaemon(true); return t;
    });
    private final ScheduledExecutorService debouncer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "docfinder-watch-debounce"); t.setDaemon(true); return t;
    });
    private final Map<Path, ScheduledFuture<?>> pending = new ConcurrentHashMap<>();
    private volatile boolean running = false;

    public LocalRecursiveWatcher(List<Path> roots, Listener listener) throws IOException {
        this.roots = new ArrayList<>(roots);
        this.listener = listener;
        this.ws = FileSystems.getDefault().newWatchService();
    }

    public void start() throws IOException {
        if (running) return;
        running = true;
        // 递归注册所有现有子目录
        for (Path root : roots) {
            if (root == null || !Files.exists(root)) continue;
            try {
                Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                    @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        try {
                            registerDir(dir);
                        } catch (Exception e) {
                            // Log or ignore
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (Exception e) {
                // Log or ignore
            }
        }
        loop.submit(this::loopRun);
    }

    private void loopRun() {
        while (running) {
            WatchKey key;
            try {
                key = ws.take(); // 阻塞
            } catch (InterruptedException e) { break; }
            Path dir = key2dir.get(key);
            if (dir == null) { key.reset(); continue; }

            for (WatchEvent<?> ev : key.pollEvents()) {
                WatchEvent.Kind<?> kind = ev.kind();
                if (kind == OVERFLOW) continue;

                @SuppressWarnings("unchecked")
                Path name = ((WatchEvent<Path>) ev).context();
                Path child = dir.resolve(name);

                // 新目录创建：递归注册
                if (kind == ENTRY_CREATE) {
                    try { if (Files.isDirectory(child)) registerTree(child); } catch (Exception ignore) {}
                }

                // 去抖：同一路径 500ms 合并，只保留最后一次类型
                debounce(child, kind);
            }
            key.reset();
        }
    }

    private void debounce(Path child, WatchEvent.Kind<?> kind) {
        ScheduledFuture<?> old = pending.get(child);
        if (old != null) old.cancel(false);

        String type = (kind == ENTRY_DELETE) ? "DELETE"
                    : (kind == ENTRY_CREATE) ? "CREATE" : "MODIFY";

        pending.put(child, debouncer.schedule(() -> {
            pending.remove(child);
            try { listener.onChange(type, child); } catch (Throwable ignore) {}
        }, 500, TimeUnit.MILLISECONDS));
    }

    private void registerTree(Path root) {
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    try {
                        registerDir(dir);
                    } catch (Exception e) {
                        // Ignore
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (Exception e) {
            // Ignore
        }
    }

    private void registerDir(Path dir) throws IOException {
        WatchKey key = dir.register(ws, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE);
        key2dir.put(key, dir);
    }

    @Override public void close() throws IOException {
        running = false;
        try { ws.close(); } catch (Exception ignore) {}
        loop.shutdownNow();
        debouncer.shutdownNow();
        for (ScheduledFuture<?> f : pending.values()) { try { f.cancel(false); } catch (Exception ignore) {} }
        pending.clear();
        key2dir.clear();
    }
}
