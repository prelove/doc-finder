package org.abitware.docfinder.watch;

import org.abitware.docfinder.index.IndexSettings;
import org.abitware.docfinder.index.IndexWriteCoordinator;
import org.abitware.docfinder.index.LuceneIndexer;
import org.abitware.docfinder.util.IsolatedFileAccess;
import org.abitware.docfinder.util.PathScope;
import org.abitware.docfinder.util.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

public class LiveIndexService implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(LiveIndexService.class);
    private static final int MAX_BATCH_SIZE = 500;
    private static final int WRITE_BATCH_SIZE = 16;
    private static final long FLUSH_INTERVAL_MS = 700L;
    private static final int MAX_PENDING_CHANGES = 50_000;
    private enum ChangeType { CREATE, MODIFY, DELETE }

    private final Path indexDir;
    private final IndexSettings settings;
    private final List<Path> roots;
    private final Runnable onAfterCommit;
    private final NetPollerService reconciler;
    // 虚拟线程在 monitor 内阻塞会把载体线程固定住；pending 状态域含
    // markReconcileLocked 的日志写（可能触达文件 appender），lifecycle 域
    // 覆盖 watcher 构造与 applyPrepared 的 writer/commit/close 磁盘 I/O，
    // 两个域各自整体迁移到 ReentrantLock，不能混用 monitor 与新锁。
    private final ReentrantLock pendingLock = new ReentrantLock();
    private final ReentrantLock lifecycleLock = new ReentrantLock();
    private final LinkedHashMap<Path, ChangeType> pendingChanges = new LinkedHashMap<>();
    private final Set<Path> reconcileRoots = new LinkedHashSet<>();
    private final AtomicBoolean drainActive = new AtomicBoolean();
    private volatile boolean closed;
    private long generation;
    private LocalRecursiveWatcher watcher;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "docfinder-liveindex-worker"); thread.setDaemon(true); return thread;
    });
    private final ScheduledExecutorService flusher = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "docfinder-liveindex-flush"); thread.setDaemon(true); return thread;
    });

    public LiveIndexService(Path indexDir, IndexSettings settings, List<Path> roots) { this(indexDir, settings, roots, null); }
    public LiveIndexService(Path indexDir, IndexSettings settings, List<Path> roots, Runnable onAfterCommit) {
        this.indexDir = indexDir; this.settings = settings; this.roots = new ArrayList<>(roots); this.onAfterCommit = onAfterCommit;
        this.reconciler = new NetPollerService(indexDir, settings, roots, onAfterCommit);
    }
    public void start() throws Exception {
        lifecycleLock.lock();
        try {
            if (closed) throw new IllegalStateException("Live indexing service closed");
            if (watcher != null) return;
            watcher = new LocalRecursiveWatcher(roots, new LocalRecursiveWatcher.Listener() {
                @Override public void onChange(String type, Path path) { LiveIndexService.this.onChange(type, path); }
                @Override public void onOverflow(Path dir) { LiveIndexService.this.onOverflow(dir); }
            });
            watcher.start();
            // Watch registration only sees future events. Reuse the bounded
            // reconciliation queue to repair creates/deletes missed while the
            // application (or this service) was stopped.
            pendingLock.lock();
            try {
                for (Path root : roots) if (root != null) reconcileRoots.add(root);
            } finally {
                pendingLock.unlock();
            }
            flusher.scheduleWithFixedDelay(this::flushPendingSafe, FLUSH_INTERVAL_MS, FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
        } finally {
            lifecycleLock.unlock();
        }
    }
    void onChange(String type, Path path) {
        if (path == null || closed) return;
        ChangeType incoming = toChangeType(type);
        pendingLock.lock();
        try {
            if (closed) return;
            ChangeType current = pendingChanges.get(path);
            ChangeType merged = merge(current, incoming);
            if (merged == null) pendingChanges.remove(path);
            else if (current == null && pendingChanges.size() >= MAX_PENDING_CHANGES) markReconcileLocked(path);
            else pendingChanges.put(path, merged);
            // A deleted directory no longer has attributes with which to identify its descendants.
            if (incoming == ChangeType.DELETE) markReconcileLocked(path);
        } finally {
            pendingLock.unlock();
        }
    }
    void onOverflow(Path dir) {
        if (dir == null || closed) return;
        pendingLock.lock();
        try {
            if (!closed) markReconcileLocked(dir);
        } finally {
            pendingLock.unlock();
        }
    }
    private void markReconcileLocked(Path path) {
        for (Path root : roots) if (root != null && path.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize())) {
            if (reconcileRoots.add(root)) log.warn("Watch source {} requires reconciliation; retry remains pending until successful", root);
            return;
        }
        log.warn("Watch overflow outside configured roots: {}", path);
    }
    Set<Path> pendingReconcileRoots() {
        pendingLock.lock();
        try {
            return new LinkedHashSet<>(reconcileRoots);
        } finally {
            pendingLock.unlock();
        }
    }
    private void flushPendingSafe() {
        try { flushPending(); } catch (RuntimeException e) { log.warn("Live watch flush failed", e); }
    }
    private void flushPending() {
        if (closed || !drainActive.compareAndSet(false, true)) return;
        try { worker.submit(this::drainPending); }
        catch (RejectedExecutionException e) { drainActive.set(false); }
    }
    private void drainPending() {
        boolean retryLater = false;
        final long token;
        lifecycleLock.lock();
        try {
            token = generation;
        } finally {
            lifecycleLock.unlock();
        }
        try {
            while (valid(token)) {
                Path reconcile = pollReconcileRoot();
                Map<Path, ChangeType> batch = takeBatch(MAX_BATCH_SIZE);
                if (reconcile == null && batch.isEmpty()) return;
                if (reconcile != null && !reconciler.reconcile(reconcile)) {
                    pendingLock.lock();
                    try {
                        if (!closed) reconcileRoots.add(reconcile);
                    } finally {
                        pendingLock.unlock();
                    }
                    retryLater = true;
                }
                if (!applyBatch(batch, token)) {
                    restore(batch);
                    retryLater = true;
                }
                // A failed source or writer retries on the next scheduled flush, avoiding a busy failure loop.
                if (retryLater) return;
            }
        } finally {
            drainActive.set(false);
            pendingLock.lock();
            try {
                if (!retryLater && !closed && (!pendingChanges.isEmpty() || !reconcileRoots.isEmpty())) flushPending();
            } finally {
                pendingLock.unlock();
            }
        }
    }
    private boolean valid(long token) {
        lifecycleLock.lock();
        try {
            return !closed && generation == token && !Thread.currentThread().isInterrupted();
        } finally {
            lifecycleLock.unlock();
        }
    }
    private void requireValid(long token) throws IOException {
        if (!valid(token)) throw new InterruptedIOException("Live indexing cancelled");
    }

    /** Preparation and file stability probes stay outside the shared index writer lock. */
    private boolean applyBatch(Map<Path, ChangeType> remaining, long token) {
        List<PreparedChange> prepared = new ArrayList<>(WRITE_BATCH_SIZE);
        try {
            for (Map.Entry<Path, ChangeType> entry : new ArrayList<>(remaining.entrySet())) {
                requireValid(token);
                Path path = entry.getKey();
                long preparedAt = System.nanoTime();
                LuceneIndexer.PreparedUpsert document = null;
                if (entry.getValue() == ChangeType.DELETE) {
                    Path root = containingRoot(path);
                    if (root == null) throw new IOException("Watch deletion outside configured roots: " + path);
                    IsolatedFileAccess.Classification verdict = IsolatedFileAccess.classify(path.toString(),
                            PathScope.normalizeIndexedPath(root), 2000);
                    if (verdict == IsolatedFileAccess.Classification.ALIVE) {
                        document = LuceneIndexer.prepareUpsert(path, indexDir, settings);
                    } else if (verdict != IsolatedFileAccess.Classification.GHOST) {
                        throw new IOException("Watch deletion is not confirmed: " + path);
                    }
                } else {
                    try {
                        if (LuceneIndexer.shouldIndexPath(path, indexDir, settings)) waitStable(path, 2000);
                        document = LuceneIndexer.prepareUpsert(path, indexDir, settings);
                    } catch (NoSuchFileException e) {
                        // A known absence after CREATE/MODIFY converges to deletion; access errors remain retryable.
                    }
                }
                prepared.add(new PreparedChange(path, document, preparedAt));
                if (prepared.size() == WRITE_BATCH_SIZE) {
                    applyPrepared(prepared, token);
                    for (PreparedChange change : prepared) remaining.remove(change.path);
                    prepared.clear();
                }
            }
            if (!prepared.isEmpty()) {
                applyPrepared(prepared, token);
                for (PreparedChange change : prepared) remaining.remove(change.path);
            }
            return true;
        } catch (Exception e) {
            log.warn("Live watch batch retained for retry: {}", e.toString());
            return false;
        }
    }
    private void applyPrepared(List<PreparedChange> changes, long token) throws Exception {
        IndexWriteCoordinator.call(() -> {
            // 锁序固定 WRITE_LOCK → lifecycleLock：本锁在 coordinator 内部获取，
            // 持锁完成 writer 打开/写入/commit/close（明确跨磁盘 I/O，正是不能
            // 留在 monitor 内的原因）。
            lifecycleLock.lock();
            try {
                requireValid(token);
                try (LuceneIndexer indexer = new LuceneIndexer(indexDir, settings)) {
                    for (PreparedChange change : changes) {
                        requireValid(token);
                        if (change.document == null && LuceneIndexer.hasNewerUpsert(
                                Utils.normalizeForIndex(change.path), change.preparedAt)) {
                            throw new IOException("Watch deletion superseded by a newer write: " + change.path);
                        }
                        boolean applied = change.document == null ? indexer.tryDeletePath(change.path) : indexer.applyPrepared(change.document);
                        if (!applied) throw new IOException("Prepared change superseded: " + change.path);
                    }
                    indexer.commit();
                }
            } finally {
                lifecycleLock.unlock();
            }
            return null;
        });
        if (onAfterCommit != null && valid(token)) {
            try { onAfterCommit.run(); } catch (RuntimeException e) { log.warn("Live index refresh failed", e); }
        }
    }
    private static final class PreparedChange {
        final Path path;
        final LuceneIndexer.PreparedUpsert document;
        final long preparedAt;
        PreparedChange(Path path, LuceneIndexer.PreparedUpsert document, long preparedAt) {
            this.path = path; this.document = document; this.preparedAt = preparedAt;
        }
    }
    private Path containingRoot(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        Path selected = null;
        for (Path root : roots) {
            if (root != null && normalized.startsWith(root.toAbsolutePath().normalize())
                    && (selected == null || root.getNameCount() > selected.getNameCount())) selected = root;
        }
        return selected;
    }
    private void restore(Map<Path, ChangeType> remaining) {
        pendingLock.lock();
        try {
            if (closed) return;
            for (Map.Entry<Path, ChangeType> entry : remaining.entrySet()) {
                // Events received during the failed write are newer and win over the retry.
                if (pendingChanges.containsKey(entry.getKey())) continue;
                if (pendingChanges.size() < MAX_PENDING_CHANGES) pendingChanges.put(entry.getKey(), entry.getValue());
                else markReconcileLocked(entry.getKey());
            }
        } finally {
            pendingLock.unlock();
        }
    }
    private Path pollReconcileRoot() {
        pendingLock.lock();
        try {
            Iterator<Path> iterator = reconcileRoots.iterator();
            if (!iterator.hasNext()) return null;
            Path root = iterator.next(); iterator.remove(); return root;
        } finally {
            pendingLock.unlock();
        }
    }
    private Map<Path, ChangeType> takeBatch(int limit) {
        pendingLock.lock();
        try {
            Map<Path, ChangeType> batch = new LinkedHashMap<>();
            Iterator<Map.Entry<Path, ChangeType>> iterator = pendingChanges.entrySet().iterator();
            while (iterator.hasNext() && batch.size() < limit) {
                Map.Entry<Path, ChangeType> entry = iterator.next();
                batch.put(entry.getKey(), entry.getValue()); iterator.remove();
            }
            return batch;
        } finally {
            pendingLock.unlock();
        }
    }
    private static ChangeType merge(ChangeType current, ChangeType incoming) {
        if (incoming == null) return current;
        if (current == null) return incoming;
        if (current == ChangeType.CREATE) return incoming == ChangeType.DELETE ? null : ChangeType.CREATE;
        if (current == ChangeType.MODIFY) return incoming == ChangeType.DELETE ? ChangeType.DELETE : ChangeType.MODIFY;
        return incoming == ChangeType.DELETE ? ChangeType.DELETE : ChangeType.MODIFY;
    }
    private static ChangeType toChangeType(String type) {
        return "DELETE".equals(type) ? ChangeType.DELETE : "CREATE".equals(type) ? ChangeType.CREATE : ChangeType.MODIFY;
    }
    private void waitStable(Path path, long maxMs) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(maxMs);
        long size = -1, modified = -1;
        while (System.nanoTime() < deadline) {
            if (closed || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Stability check cancelled");
            IsolatedFileAccess.Attributes attrs = IsolatedFileAccess.attributes(path, 2000);
            if (attrs.isDirectory() || attrs.getSize() == size && attrs.getLastModifiedTime() == modified) return;
            size = attrs.getSize(); modified = attrs.getLastModifiedTime();
            try { Thread.sleep(300); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new InterruptedIOException("Stability check interrupted"); }
        }
        throw new IOException("File still changing: " + path);
    }
    @Override public void close() {
        lifecycleLock.lock();
        try {
            closed = true; generation++;
        } finally {
            lifecycleLock.unlock();
        }
        reconciler.close();
        try { if (watcher != null) watcher.close(); } catch (Exception e) { log.debug("Watcher close failed", e); }
        watcher = null;
        flusher.shutdownNow();
        pendingLock.lock();
        try {
            pendingChanges.clear(); reconcileRoots.clear();
        } finally {
            pendingLock.unlock();
        }
        worker.shutdownNow();
    }
}
