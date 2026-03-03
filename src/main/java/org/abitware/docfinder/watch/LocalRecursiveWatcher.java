package org.abitware.docfinder.watch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

public class LocalRecursiveWatcher implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(LocalRecursiveWatcher.class);

    public interface Listener {
        // type: "CREATE" | "MODIFY" | "DELETE"
        void onChange(String type, Path path);
    }

    private final List<Path> roots;
    private final Listener listener;
    private final WatchService ws;
    private final Map<WatchKey, Path> key2dir = new ConcurrentHashMap<>();
    private final ExecutorService loop = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "docfinder-watch-loop");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService bootstrap = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "docfinder-watch-bootstrap");
        t.setDaemon(true);
        return t;
    });
    private final ScheduledExecutorService debouncer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "docfinder-watch-debounce");
        t.setDaemon(true);
        return t;
    });
    private final Map<Path, ScheduledFuture<?>> pending = new ConcurrentHashMap<>();
    private volatile boolean running = false;

    public LocalRecursiveWatcher(List<Path> roots, Listener listener) throws IOException {
        this.roots = new ArrayList<>(roots);
        this.listener = listener;
        this.ws = FileSystems.getDefault().newWatchService();
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;

        loop.submit(this::loopRun);

        // 快速返回：先注册根目录，再在后台递归注册子目录，避免大盘符启动卡住
        for (Path root : roots) {
            try {
                if (root == null || !Files.exists(root) || !Files.isDirectory(root)) {
                    continue;
                }
                registerDir(root);
                bootstrap.submit(() -> registerExistingSubDirs(root));
            } catch (Throwable t) {
                log.warn("Watch startup register root error: {}, exception: {}", root, t.getMessage());
            }
        }
    }

    private void registerExistingSubDirs(Path root) {
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!running) {
                        return FileVisitResult.TERMINATE;
                    }
                    if (!root.equals(dir)) {
                        try {
                            registerDir(dir);
                        } catch (Throwable t) {
                            log.debug("Watch register dir skipped: {}", dir);
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (Throwable t) {
            log.warn("Watch bootstrap walk failed for root: {}, exception: {}", root, t.getMessage());
        }
    }

    private void loopRun() {
        while (running) {
            WatchKey key;
            try {
                key = ws.take(); // 阻塞
            } catch (InterruptedException e) {
                break;
            } catch (Throwable t) {
                break;
            }
            Path dir = key2dir.get(key);
            if (dir == null) {
                key.reset();
                continue;
            }

            for (WatchEvent<?> ev : key.pollEvents()) {
                WatchEvent.Kind<?> kind = ev.kind();
                if (kind == OVERFLOW) {
                    continue;
                }

                @SuppressWarnings("unchecked")
                Path name = ((WatchEvent<Path>) ev).context();
                Path child = dir.resolve(name);

                // 新目录创建：异步递归注册
                if (kind == ENTRY_CREATE) {
                    try {
                        if (Files.isDirectory(child)) {
                            bootstrap.submit(() -> registerExistingSubDirs(child));
                        }
                    } catch (Exception ignore) {
                    }
                }

                // 去抖：同一路径 500ms 合并，只保留最后一次类型
                debounce(child, kind);
            }
            key.reset();
        }
    }

    private void debounce(Path child, WatchEvent.Kind<?> kind) {
        ScheduledFuture<?> old = pending.get(child);
        if (old != null) {
            old.cancel(false);
        }

        String type = (kind == ENTRY_DELETE) ? "DELETE" : (kind == ENTRY_CREATE) ? "CREATE" : "MODIFY";

        pending.put(child, debouncer.schedule(() -> {
            pending.remove(child);
            try {
                listener.onChange(type, child);
            } catch (Throwable ignore) {
            }
        }, 500, TimeUnit.MILLISECONDS));
    }

    private void registerDir(Path dir) throws IOException {
        WatchKey key = dir.register(ws, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE);
        key2dir.put(key, dir);
    }

    @Override
    public void close() throws IOException {
        running = false;
        try {
            ws.close();
        } catch (Exception ignore) {
        }
        loop.shutdownNow();
        bootstrap.shutdownNow();
        debouncer.shutdownNow();
        for (ScheduledFuture<?> f : pending.values()) {
            try {
                f.cancel(false);
            } catch (Exception ignore) {
            }
        }
        pending.clear();
        key2dir.clear();
    }
}
