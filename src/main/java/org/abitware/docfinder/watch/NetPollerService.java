package org.abitware.docfinder.watch;

import org.abitware.docfinder.index.IndexSettings;
import org.abitware.docfinder.index.IndexWriteCoordinator;
import org.abitware.docfinder.index.LuceneIndexer;
import org.abitware.docfinder.util.Utils;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexNotFoundException;
import org.apache.lucene.index.MultiBits;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.Bits;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class NetPollerService implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(NetPollerService.class);
    private static final int MAX_SCAN_THREADS = 4;
    private static final int WRITE_BATCH_SIZE = 16;
    private final Path indexDir;
    private final IndexSettings settings;
    private final List<Path> roots;
    private final SnapshotStore store;
    private final Runnable onAfterCommit;
    private final Object lifecycle = new Object();
    private final AtomicBoolean pollInFlight = new AtomicBoolean();
    private final AtomicInteger pendingRoots = new AtomicInteger();
    private volatile boolean running;
    private volatile boolean closed;
    private long generation;
    private Round activeRound;
    private ScheduledExecutorService scheduler;
    private ExecutorService onDemand;
    private ThreadPoolExecutor scanPool;

    public NetPollerService(Path indexDir, IndexSettings settings, List<Path> roots) {
        this(indexDir, settings, roots, null);
    }
    public NetPollerService(Path indexDir, IndexSettings settings, List<Path> roots, Runnable onAfterCommit) {
        this(indexDir, settings, roots, onAfterCommit, new SnapshotStore());
    }
    NetPollerService(Path indexDir, IndexSettings settings, List<Path> roots, Runnable onAfterCommit, SnapshotStore store) {
        this.indexDir = indexDir;
        this.settings = settings;
        this.roots = new ArrayList<>(roots);
        this.onAfterCommit = onAfterCommit;
        this.store = store;
        this.onDemand = newOnDemand();
        this.scanPool = newScanPool();
    }

    private static ThreadFactory threads(String name) {
        return runnable -> { Thread thread = new Thread(runnable, name); thread.setDaemon(true); return thread; };
    }
    private static ThreadPoolExecutor newScanPool() {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(MAX_SCAN_THREADS, MAX_SCAN_THREADS, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<Runnable>(64), threads("docfinder-netpoll-scan"));
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }
    private static ExecutorService newOnDemand() {
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<Runnable>(1), threads("docfinder-netpoll-once"));
    }

    public static class PollStats {
        public boolean accepted = true;
        public boolean successful;
        public boolean cancelled;
        public long failedRoots;
        public long scannedFiles;
        public long created;
        public long modified;
        public long deleted;
        public long durationMs;
    }
    static class RootStats {
        long scannedFiles, created, modified, deleted;
        boolean complete;
    }

    public void start(int minutes) {
        stop();
        synchronized (lifecycle) {
            closed = false;
            running = true;
            scanPool = newScanPool();
            onDemand = newOnDemand();
            scheduler = Executors.newSingleThreadScheduledExecutor(threads("docfinder-netpoller"));
            scheduler.scheduleWithFixedDelay(this::pollOnceSafe, 3, Math.max(1, minutes) * 60L, TimeUnit.SECONDS);
        }
    }

    /** Reservation happens at the entry point, so queued manual requests cannot impersonate completed polls. */
    public Future<PollStats> pollNowAsync() {
        synchronized (lifecycle) {
            Round round = reserve(roots, false);
            if (round == null) return CompletableFuture.completedFuture(rejected());
            RoundFuture task = new RoundFuture(round);
            round.future = task;
            try { onDemand.execute(task); }
            catch (RejectedExecutionException e) { task.cancel(false); }
            return task;
        }
    }
    public void pollNow() { pollBlocking(roots, false); }

    /** Live overflow reconciliation compares the complete source with the actual indexed root. */
    boolean reconcile(Path root) { return pollBlocking(Collections.singletonList(root), true).successful; }

    private PollStats pollBlocking(List<Path> selectedRoots, boolean indexedBaseline) {
        Round round;
        synchronized (lifecycle) { round = reserve(selectedRoots, indexedBaseline); }
        if (round == null) return rejected();
        try { return runRound(round); }
        finally { bodyFinished(round); }
    }
    private void pollOnceSafe() {
        if (!running || closed) return;
        try { pollBlocking(roots, false); }
        catch (RuntimeException e) { log.warn("Scheduled poll failed", e); }
    }
    private static PollStats rejected() {
        PollStats stats = new PollStats(); stats.accepted = false; return stats;
    }
    private Round reserve(List<Path> selectedRoots, boolean indexedBaseline) {
        if (closed || activeRound != null) return null;
        Round round = new Round(generation, selectedRoots, indexedBaseline, scanPool);
        activeRound = round;
        pollInFlight.set(true);
        return round;
    }
    private boolean valid(Round round) {
        synchronized (lifecycle) {
            return !closed && !round.cancelled && round.generation == generation && !Thread.currentThread().isInterrupted();
        }
    }
    private void requireValid(Round round) throws IOException {
        if (!valid(round)) throw new InterruptedIOException("Poll round cancelled");
    }

    private PollStats runRound(Round round) {
        long start = System.currentTimeMillis();
        PollStats stats = new PollStats();
        List<RootTask> tasks = new ArrayList<>();
        try {
            for (Path root : round.roots) {
                if (!valid(round)) break;
                if (root == null) { stats.failedRoots++; continue; }
                RootTask task = new RootTask(round, root);
                synchronized (lifecycle) {
                    if (!valid(round)) break;
                    pendingRoots.incrementAndGet();
                    round.tasks.add(task);
                    tasks.add(task);
                    try { round.pool.execute(task); }
                    catch (RejectedExecutionException e) { task.cancel(false); }
                }
            }
            for (RootTask task : tasks) {
                try {
                    RootStats result = task.get();
                    stats.scannedFiles += result.scannedFiles;
                    stats.created += result.created;
                    stats.modified += result.modified;
                    stats.deleted += result.deleted;
                    if (!result.complete) stats.failedRoots++;
                } catch (InterruptedException e) {
                    cancelRound(round);
                    Thread.currentThread().interrupt();
                    break;
                } catch (CancellationException | ExecutionException e) { stats.failedRoots++; }
            }
            stats.cancelled = !valid(round);
            stats.successful = !stats.cancelled && stats.failedRoots == 0 && tasks.size() == round.roots.size();
            if (onAfterCommit != null && valid(round)) onAfterCommit.run();
        } finally { stats.durationMs = System.currentTimeMillis() - start; }
        return stats;
    }

    private RootStats processRoot(Path root, Round round) {
        RootStats stats = new RootStats();
        try (SnapshotStore.Cursor old = round.indexedBaseline ? indexedSnapshot(root) : store.open(root);
             SnapshotStore.Builder builder = store.newBuilder()) {
            requireValid(round);
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                private void record(Path path, BasicFileAttributes attrs) throws IOException {
                    requireValid(round);
                    builder.add(Utils.normalizeForIndex(path), new SnapshotStore.Entry(attrs.isDirectory() ? 0 : attrs.size(),
                            attrs.lastModifiedTime().toMillis(), attrs.isDirectory()));
                    if (!attrs.isDirectory()) stats.scannedFiles++;
                }
                @Override public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attrs) throws IOException {
                    if (!LuceneIndexer.shouldIndexPath(path, indexDir, settings)) return FileVisitResult.SKIP_SUBTREE;
                    record(path, attrs); return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) throws IOException {
                    if (LuceneIndexer.shouldIndexPath(path, indexDir, settings)) record(path, attrs);
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFileFailed(Path path, IOException failure) throws IOException {
                    throw failure == null ? new IOException("Cannot read " + path) : failure;
                }
                @Override public FileVisitResult postVisitDirectory(Path path, IOException failure) throws IOException {
                    if (failure != null) throw failure;
                    requireValid(round); return FileVisitResult.CONTINUE;
                }
            });
            try (SnapshotStore.Staged snapshot = builder.finish()) {
                try (SnapshotStore.Cursor current = snapshot.cursor()) { mergeAndApply(old, current, round, stats); }
                old.close();
                // stop/cancel and publication share this lock; no skipped apply can publish a baseline.
                synchronized (lifecycle) {
                    requireValid(round);
                    snapshot.publish(root);
                    stats.complete = true;
                }
            }
        } catch (Exception e) {
            log.warn("Poll root {} incomplete; previous baseline retained: {}", root, e.toString());
        }
        return stats;
    }

    private SnapshotStore.Cursor indexedSnapshot(Path root) throws IOException {
        try (SnapshotStore.Builder builder = store.newBuilder()) {
            try (FSDirectory directory = FSDirectory.open(indexDir); DirectoryReader reader = DirectoryReader.open(directory)) {
                Bits live = MultiBits.getLiveDocs(reader);
                for (int id = 0; id < reader.maxDoc(); id++) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Reconcile cancelled");
                    if (live != null && !live.get(id)) continue;
                    Document doc = reader.document(id, new HashSet<>(Arrays.asList("path", "size", "mtime", "kind")));
                    String path = doc.get("path");
                    if (path == null || !Paths.get(path).toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize())) continue;
                    long size = doc.getField("size") == null ? 0 : doc.getField("size").numericValue().longValue();
                    long mtime = doc.getField("mtime") == null ? 0 : doc.getField("mtime").numericValue().longValue();
                    builder.add(path, new SnapshotStore.Entry(size, mtime, "folder".equals(doc.get("kind"))));
                }
            } catch (IndexNotFoundException e) { /* A new index has no prior documents. */ }
            SnapshotStore.Staged snapshot = builder.finish();
            return snapshot.ownedCursor();
        }
    }

    private void mergeAndApply(SnapshotStore.Cursor old, SnapshotStore.Cursor current, Round round, RootStats stats) throws Exception {
        SnapshotStore.Record before = old.next(), after = current.next();
        List<Change> changes = new ArrayList<>(WRITE_BATCH_SIZE);
        while (before != null || after != null) {
            requireValid(round);
            int order = before == null ? 1 : after == null ? -1 : before.path.compareTo(after.path);
            if (order < 0) {
                changes.add(new Change(Paths.get(before.path), null, -1)); before = old.next();
            } else if (order > 0) {
                Path path = Paths.get(after.path);
                changes.add(new Change(path, LuceneIndexer.prepareUpsert(path, indexDir, settings), 1)); after = current.next();
            } else {
                Path path = Paths.get(after.path);
                SnapshotStore.Entry a = before.entry, b = after.entry;
                if (a.size != b.size || a.mtime != b.mtime || a.directory != b.directory
                        || !b.directory && LuceneIndexer.contentRetryDue(path, b.size, b.mtime)) {
                    changes.add(new Change(path, LuceneIndexer.prepareUpsert(path, indexDir, settings), 0));
                }
                before = old.next(); after = current.next();
            }
            if (changes.size() == WRITE_BATCH_SIZE) { apply(changes, round, stats); changes.clear(); }
        }
        if (!changes.isEmpty()) apply(changes, round, stats);
        requireValid(round);
    }

    private void apply(List<Change> changes, Round round, RootStats stats) throws Exception {
        IndexWriteCoordinator.call(() -> {
            synchronized (lifecycle) {
                requireValid(round);
                try (LuceneIndexer indexer = new LuceneIndexer(indexDir, settings)) {
                    for (Change change : changes) {
                        requireValid(round);
                        boolean applied = change.prepared == null ? indexer.tryDeletePath(change.path) : indexer.applyPrepared(change.prepared);
                        if (!applied) throw new IOException("Concurrent update superseded prepared change: " + change.path);
                    }
                    indexer.commit();
                }
                for (Change change : changes) {
                    if (change.kind < 0) stats.deleted++;
                    else if (change.kind > 0) stats.created++;
                    else stats.modified++;
                }
            }
            return null;
        });
    }
    private static final class Change {
        final Path path;
        final LuceneIndexer.PreparedUpsert prepared;
        final int kind;
        Change(Path path, LuceneIndexer.PreparedUpsert prepared, int kind) { this.path = path; this.prepared = prepared; this.kind = kind; }
    }

    /** Compatibility helper for existing map-based callers; production always uses the streaming merge. */
    void applySnapshotDiff(LuceneIndexer indexer, Map<String, SnapshotStore.Entry> old, Map<String, SnapshotStore.Entry> current,
                           boolean completeScan, RootStats stats) {
        for (Map.Entry<String, SnapshotStore.Entry> entry : old.entrySet()) {
            SnapshotStore.Entry next = current.get(entry.getKey());
            try {
                Path path = Paths.get(entry.getKey());
                if (next == null && completeScan) { if (indexer.tryDeletePath(path)) stats.deleted++; }
                else if (next != null && (entry.getValue().size != next.size || entry.getValue().mtime != next.mtime)) {
                    if (indexer.applyPrepared(LuceneIndexer.prepareUpsert(path, indexDir, settings))) stats.modified++;
                }
            } catch (IOException e) { throw new IllegalStateException(e); }
        }
        for (String path : current.keySet()) if (!old.containsKey(path)) {
            try { if (indexer.applyPrepared(LuceneIndexer.prepareUpsert(Paths.get(path), indexDir, settings))) stats.created++; }
            catch (IOException e) { throw new IllegalStateException(e); }
        }
    }

    private final class Round {
        final long generation;
        final List<Path> roots;
        final boolean indexedBaseline;
        final ThreadPoolExecutor pool;
        final List<RootTask> tasks = new ArrayList<>();
        volatile boolean cancelled;
        boolean bodyDone;
        RoundFuture future;
        Round(long generation, List<Path> roots, boolean indexedBaseline, ThreadPoolExecutor pool) {
            this.generation = generation; this.roots = new ArrayList<>(roots); this.indexedBaseline = indexedBaseline; this.pool = pool;
        }
    }
    private final class RoundFuture extends FutureTask<PollStats> {
        final Round round;
        private boolean started;
        RoundFuture(Round round) {
            super(() -> { try { return runRound(round); } finally { bodyFinished(round); } });
            this.round = round;
        }
        @Override public void run() {
            synchronized (this) { if (isCancelled()) return; started = true; }
            try { super.run(); } finally { bodyFinished(round); }
        }
        @Override protected void done() { synchronized (this) { if (!started) bodyFinished(round); } }
        @Override public boolean cancel(boolean interrupt) { cancelRound(round); return super.cancel(interrupt); }
    }
    private final class RootTask extends FutureTask<RootStats> {
        final Round round;
        private boolean started;
        private final AtomicBoolean counted = new AtomicBoolean();
        RootTask(Round round, Path root) { super(() -> processRoot(root, round)); this.round = round; }
        @Override public void run() {
            synchronized (this) { if (isCancelled()) return; started = true; }
            try { super.run(); } finally { finished(); }
        }
        @Override protected void done() { synchronized (this) { if (!started || !isCancelled()) finished(); } }
        private void finished() {
            if (!counted.compareAndSet(false, true)) return;
            synchronized (lifecycle) { pendingRoots.decrementAndGet(); releaseIfDrained(round); }
        }
    }
    private void bodyFinished(Round round) {
        synchronized (lifecycle) { round.bodyDone = true; releaseIfDrained(round); }
    }
    private void releaseIfDrained(Round round) {
        if (activeRound == round && round.bodyDone && pendingRoots.get() == 0) {
            activeRound = null; pollInFlight.set(false);
        }
    }
    private void cancelRound(Round round) {
        List<RootTask> tasks;
        synchronized (lifecycle) { round.cancelled = true; tasks = new ArrayList<>(round.tasks); }
        for (RootTask task : tasks) task.cancel(true);
    }
    public void stop() {
        Round round;
        ExecutorService scheduled, manual, scans;
        synchronized (lifecycle) {
            running = false; closed = true; generation++;
            round = activeRound;
            if (round != null) round.cancelled = true;
            scheduled = scheduler; manual = onDemand; scans = scanPool;
            scheduler = null; onDemand = null; scanPool = null;
        }
        if (round != null) {
            cancelRound(round);
            if (round.future != null) round.future.cancel(true);
        }
        if (scheduled != null) scheduled.shutdownNow();
        if (manual != null) manual.shutdownNow();
        if (scans != null) scans.shutdownNow();
    }
    @Override public void close() { stop(); }
}

