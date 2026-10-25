package org.abitware.docfinder.watch;

import org.abitware.docfinder.index.IndexSettings;
import org.abitware.docfinder.index.IndexWriteCoordinator;
import org.abitware.docfinder.index.LuceneIndexer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private final Object pendingLock = new Object();
    private final Object lifecycle = new Object();
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
        synchronized (lifecycle) {
            if (closed) throw new IllegalStateException("Live indexing service closed");
            if (watcher != null) return;
            watcher = new LocalRecursiveWatcher(roots, new LocalRecursiveWatcher.Listener() {
                @Override public void onChange(String type, Path path) { LiveIndexService.this.onChange(type, path); }
                @Override public void onOverflow(Path dir) { LiveIndexService.this.onOverflow(dir); }
            });
            watcher.start();
            flusher.scheduleWithFixedDelay(this::flushPendingSafe, FLUSH_INTERVAL_MS, FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
        }
    }
    void onChange(String type, Path path) {
        if (path == null || closed) return;
        ChangeType incoming = toChangeType(type);
        synchronized (pendingLock) {
            if (closed) return;
            ChangeType current = pendingChanges.get(path);
            ChangeType merged = merge(current, incoming);
            if (merged == null) pendingChanges.remove(path);
            else if (current == null && pendingChanges.size() >= MAX_PENDING_CHANGES) markReconcileLocked(path);
            else pendingChanges.put(path, merged);
            // A deleted directory no longer has attributes with which to identify its descendants.
            if (incoming == ChangeType.DELETE) markReconcileLocked(path);
        }
    }
    void onOverflow(Path dir) {
        if (dir == null || closed) return;
        synchronized (pendingLock) { if (!closed) markReconcileLocked(dir); }
    }
    private void markReconcileLocked(Path path) {
        for (Path root : roots) if (root != null && path.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize())) {
            if (reconcileRoots.add(root)) log.warn("Watch source {} requires reconciliation; retry remains pending until successful", root);
            return;
        }
        log.warn("Watch overflow outside configured roots: {}", path);
    }
    Set<Path> pendingReconcileRoots() { synchronized (pendingLock) { return new LinkedHashSet<>(reconcileRoots); } }
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
        synchronized (lifecycle) { token = generation; }
        try {
            while (valid(token)) {
                Path reconcile = pollReconcileRoot();
                Map<Path, ChangeType> batch = takeBatch(MAX_BATCH_SIZE);
                if (reconcile == null && batch.isEmpty()) return;
                if (reconcile != null && !reconciler.reconcile(reconcile)) {
                    synchronized (pendingLock) { if (!closed) reconcileRoots.add(reconcile); }
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
            synchronized (pendingLock) {
                if (!retryLater && !closed && (!pendingChanges.isEmpty() || !reconcileRoots.isEmpty())) flushPending();
            }
        }
    }
    private boolean valid(long token) {
        synchronized (lifecycle) { return !closed && generation == token && !Thread.currentThread().isInterrupted(); }
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
                LuceneIndexer.PreparedUpsert document = null;
                if (entry.getValue() != ChangeType.DELETE) {
                    try {
                        if (LuceneIndexer.shouldIndexPath(path, indexDir, settings)) waitStable(path, 2000);
                        document = LuceneIndexer.prepareUpsert(path, indexDir, settings);
                    } catch (NoSuchFileException e) {
                        // A known absence after CREATE/MODIFY converges to deletion; access errors remain retryable.
                    }
                }
                prepared.add(new PreparedChange(path, document));
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
            synchronized (lifecycle) {
                requireValid(token);
                try (LuceneIndexer indexer = new LuceneIndexer(indexDir, settings)) {
                    for (PreparedChange change : changes) {
                        requireValid(token);
                        boolean applied = change.document == null ? indexer.tryDeletePath(change.path) : indexer.applyPrepared(change.document);
                        if (!applied) throw new IOException("Prepared change superseded: " + change.path);
                    }
                    indexer.commit();
                }
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
        PreparedChange(Path path, LuceneIndexer.PreparedUpsert document) { this.path = path; this.document = document; }
    }
    private void restore(Map<Path, ChangeType> remaining) {
        synchronized (pendingLock) {
            if (closed) return;
            for (Map.Entry<Path, ChangeType> entry : remaining.entrySet()) {
                // Events received during the failed write are newer and win over the retry.
                if (pendingChanges.containsKey(entry.getKey())) continue;
                if (pendingChanges.size() < MAX_PENDING_CHANGES) pendingChanges.put(entry.getKey(), entry.getValue());
                else markReconcileLocked(entry.getKey());
            }
        }
    }
    private Path pollReconcileRoot() {
        synchronized (pendingLock) {
            Iterator<Path> iterator = reconcileRoots.iterator();
            if (!iterator.hasNext()) return null;
            Path root = iterator.next(); iterator.remove(); return root;
        }
    }
    private Map<Path, ChangeType> takeBatch(int limit) {
        synchronized (pendingLock) {
            Map<Path, ChangeType> batch = new LinkedHashMap<>();
            Iterator<Map.Entry<Path, ChangeType>> iterator = pendingChanges.entrySet().iterator();
            while (iterator.hasNext() && batch.size() < limit) {
                Map.Entry<Path, ChangeType> entry = iterator.next();
                batch.put(entry.getKey(), entry.getValue()); iterator.remove();
            }
            return batch;
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
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
            if (attrs.isDirectory() || attrs.size() == size && attrs.lastModifiedTime().toMillis() == modified) return;
            size = attrs.size(); modified = attrs.lastModifiedTime().toMillis();
            try { Thread.sleep(300); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new InterruptedIOException("Stability check interrupted"); }
        }
        throw new IOException("File still changing: " + path);
    }
    @Override public void close() {
        synchronized (lifecycle) { closed = true; generation++; }
        reconciler.close();
        try { if (watcher != null) watcher.close(); } catch (Exception e) { log.debug("Watcher close failed", e); }
        watcher = null;
        flusher.shutdownNow();
        synchronized (pendingLock) { pendingChanges.clear(); reconcileRoots.clear(); }
        worker.shutdownNow();
    }
}
