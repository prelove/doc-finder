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

    /** 统计信息：本次扫描的变化数与耗时 */
    public static class PollStats {
        public long scannedFiles; public long created; public long modified; public long deleted; public long durationMs;
    }

    // 周期轮询保持不变
    public void start(int minutes) {
        stop();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "docfinder-netpoller"); t.setDaemon(true); return t;
        });
        running = true;
        scheduler.scheduleWithFixedDelay(this::pollOnceSafe, 3, Math.max(1, minutes)*60L, TimeUnit.SECONDS);
    }

    /** UI 调用：启动一次轮询（异步），返回 Future，用于显示统计与刷新结果 */
    public java.util.concurrent.Future<PollStats> pollNowAsync() {
        return onDemand.submit(this::pollOnceWithStats);
    }

    public void pollNow() { // 兼容旧调用，不建议再用
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
                            log.warn("Visit file error in net poller: {}, exception: {}", file, t.getMessage());
                        }
                        return FileVisitResult.CONTINUE;
                    }
                    @Override public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (Throwable t) {
                log.error("Walk file tree error in net poller for root: {}, exception: {}", root, t.getMessage());
                        } catch (Exception e) {
                            // Ignore or log
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (Exception e) {
                // Ignore or log
            }

            Set<String> all = new HashSet<>();
            all.addAll(oldSnap.keySet());
            all.addAll(newSnap.keySet());

            LuceneIndexer idx = new LuceneIndexer(indexDir, settings);
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

            store.save(root, newSnap);
        }

        stats.durationMs = System.currentTimeMillis() - t0;
        return stats;
    }

    // ... safeUpsert/safeDelete/stop/close 保持
    public void stop() {
        running = false;
        if (scheduler != null) { scheduler.shutdownNow(); scheduler = null; }
        onDemand.shutdownNow(); // ✅ 记得停掉 onDemand 线程
    }
    
     

    private void pollOnce() throws IOException {
        for (Path root : roots) {
            if (root == null || !Files.exists(root)) continue;

            // 1) 读取旧快照
            Map<String, SnapshotStore.Entry> oldSnap = store.load(root);
            Map<String, SnapshotStore.Entry> newSnap = new HashMap<>();

            // 2) 扫描当前文件状态（应用排除规则由 LuceneIndexer 负责，这里只做基础过滤）
            try {
                Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                    @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) { return FileVisitResult.CONTINUE; }
                    @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        try {
                            if (attrs.isDirectory()) return FileVisitResult.CONTINUE;
                            String abs = file.toAbsolutePath().toString();
                            newSnap.put(abs, new SnapshotStore.Entry(attrs.size(), attrs.lastModifiedTime().toMillis()));
                        } catch (Throwable t) {
                            log.warn("Visit file error in net poller (legacy): {}, exception: {}", file, t.getMessage());
                        }
                        return FileVisitResult.CONTINUE;
                    }
                    @Override public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (Throwable t) {
                log.error("Walk file tree error in net poller (legacy) for root: {}, exception: {}", root, t.getMessage());
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

            // 3) 对比生成变更
            Set<String> all = new HashSet<>();
            all.addAll(oldSnap.keySet());
            all.addAll(newSnap.keySet());

            LuceneIndexer idx = new LuceneIndexer(indexDir, settings);
            for (String p : all) {
                SnapshotStore.Entry o = oldSnap.get(p);
                SnapshotStore.Entry n = newSnap.get(p);
                Path path = Paths.get(p);

                if (o == null && n != null) {
                    // CREATE
                    safeUpsert(idx, path);
                } else if (o != null && n == null) {
                    // DELETE
                    safeDelete(idx, path);
                } else if (o != null && n != null) {
                    // MODIFY（size 或 mtime 变化）
                    if (o.size != n.size || o.mtime != n.mtime) {
                        safeUpsert(idx, path);
                    }
                }
            }

            // 4) 保存新快照
            store.save(root, newSnap);
        }
    }

    private void safeUpsert(LuceneIndexer idx, Path p) {
        try { idx.upsertFile(p); } catch (Exception ignore) {}
    }
    private void safeDelete(LuceneIndexer idx, Path p) {
        try { idx.deletePath(p); } catch (Exception ignore) {}
    }

    @Override public void close() { stop(); }
}
