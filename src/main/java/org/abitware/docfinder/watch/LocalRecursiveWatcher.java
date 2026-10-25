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

        /**
         * Called when events may have been lost (a WatchService OVERFLOW or a
         * saturated registration backlog). The directory's containing source
         * should be treated as reconciliation-needed rather than the gap
         * being silently dropped.
         */
        default void onOverflow(Path dir) {}
    }

    /** Registration tasks queued behind the single bootstrap worker. */
    private static final int BOOTSTRAP_QUEUE_CAP = 256;
    /** Debounced paths held at once; beyond this, events fire unbatched. */
    private static final int MAX_DEBOUNCED = 20_000;

    private final List<Path> roots;
    private final Listener listener;
    private final WatchService ws;
    private final Map<WatchKey, Path> key2dir = new ConcurrentHashMap<>();
    private final ExecutorService loop = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "docfinder-watch-loop");
        t.setDaemon(true);
        return t;
    });
    /**
     * Bounded registration queue: a full queue reports the directory as a
     * reconcile-needed overflow instead of growing a backlog without bound.
     */
    private final java.util.concurrent.ThreadPoolExecutor bootstrap =
            new java.util.concurrent.ThreadPoolExecutor(1, 1,
                    0L, TimeUnit.MILLISECONDS,
                    new java.util.concurrent.ArrayBlockingQueue<Runnable>(BOOTSTRAP_QUEUE_CAP),
                    r -> {
                        Thread t = new Thread(r, "docfinder-watch-bootstrap");
                        t.setDaemon(true);
                        return t;
                    },
                    new java.util.concurrent.RejectedExecutionHandler() {
                        @Override
                        public void rejectedExecution(Runnable r, java.util.concurrent.ThreadPoolExecutor e) {
                            if (r instanceof BootstrapTask) {
                                fireOverflow(((BootstrapTask) r).dir);
                            }
                        }
                    });
    private final ScheduledExecutorService debouncer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "docfinder-watch-debounce");
        t.setDaemon(true);
        return t;
    });
    private final Map<Path, ScheduledFuture<?>> pending = new ConcurrentHashMap<>();
    private volatile boolean running = false;

    /** Directory registration task carrying its dir so a rejection can be attributed. */
    private final class BootstrapTask implements Runnable {
        final Path dir;
        BootstrapTask(Path dir) { this.dir = dir; }
        @Override
        public void run() {
            registerExistingSubDirs(dir);
        }
    }

    private void fireOverflow(Path dir) {
        try {
            listener.onOverflow(dir);
        } catch (Throwable ignore) {
        }
    }

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
                bootstrap.submit(new BootstrapTask(root));
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
                if (!key.reset()) {
                    key2dir.remove(key); // dead key — drop its registration entry
                }
                continue;
            }

            for (WatchEvent<?> ev : key.pollEvents()) {
                WatchEvent.Kind<?> kind = ev.kind();
                if (kind == OVERFLOW) {
                    fireOverflow(dir); // events lost — source needs reconciliation
                    continue;
                }

                @SuppressWarnings("unchecked")
                Path name = ((WatchEvent<Path>) ev).context();
                Path child = dir.resolve(name);

                // 新目录创建：异步递归注册
                if (kind == ENTRY_CREATE) {
                    try {
                        if (Files.isDirectory(child)) {
                            bootstrap.submit(new BootstrapTask(child));
                        }
                    } catch (Exception ignore) {
                    }
                }

                // 去抖：同一路径 500ms 合并，只保留最后一次类型
                debounce(child, kind);
            }
            if (!key.reset()) {
                // Directory became inaccessible — the key can no longer be
                // re-armed, so drop it instead of holding a dead registration.
                key2dir.remove(key);
            }
        }
    }

    private void debounce(Path child, WatchEvent.Kind<?> kind) {
        String type = (kind == ENTRY_DELETE) ? "DELETE" : (kind == ENTRY_CREATE) ? "CREATE" : "MODIFY";

        ScheduledFuture<?> old = pending.get(child);
        if (old != null) {
            old.cancel(false);
        }

        if (pending.size() >= MAX_DEBOUNCED && !pending.containsKey(child)) {
            // Debounce map at capacity: deliver immediately rather than
            // dropping the event or growing the map without bound. The
            // listener callback is a cheap map merge.
            try {
                listener.onChange(type, child);
            } catch (Throwable ignore) {
            }
            return;
        }

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
