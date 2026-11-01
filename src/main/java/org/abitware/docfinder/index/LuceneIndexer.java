package org.abitware.docfinder.index;

import org.abitware.docfinder.util.AppPaths;
import org.abitware.docfinder.util.IsolatedFileAccess;
import org.abitware.docfinder.util.PathScope;
import org.abitware.docfinder.util.Utils;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.TieredMergePolicy;
import org.apache.lucene.index.Term;
import org.apache.lucene.store.FSDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.attribute.BasicFileAttributes;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Phaser;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Lucene 索引器：
 * - 索引字段：path(规范化)、name、name_raw(小写，不分词)、ext、mtime_l(可范围过滤)、mtime/ctime/atime/size、mime、content(+zh/ja)
 * - 解析正文：只读、限时、大小/类型白名单
 * - 支持：单文件 upsert、删除；单目录/多目录全量索引（支持强制重建）
 */
public class LuceneIndexer implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(LuceneIndexer.class);
    private static final String KIND_FILE = "file";
    private static final String KIND_FOLDER = "folder";
    private static final int DEFAULT_MAX_CHARS = 1_000_000;
    private static final int MAX_ERROR_CACHE_SIZE = 10_000;
    /** 一个批次最多两个互不重叠的 source 遍历虚拟线程。 */
    static final int MAX_SOURCE_WALKERS = 2;
    /** JVM 内全量索引共用的在途任务上限：8 = 解析进程数 4 × 2，覆盖 4 个
     *  正在解析加约 4 个等待/构建/写入中的文件；文件和目录文档都计入。
     *  与 helper 池容量的关系：解析池是 PARSING_WORKER_COUNT(4) 个进程 +
     *  PARSING_QUEUE_CAPACITY(8) = 12 个槽位，索引最多占 8 个，余下 4 个
     *  是留给增量索引/恢复调用的余量，不是任何调用方预留的配额；
     *  元数据池 2+16=18 ≥ 8，索引自身不会单独耗尽该池，仍可能因其它消费者竞争而拒绝。 */
    static final int MAX_IN_FLIGHT_INDEX_TASKS = 8;
    static final Semaphore INDEX_TASK_PERMITS = new Semaphore(MAX_IN_FLIGHT_INDEX_TASKS, true);
    /** 一次调用最多保存 32 个 (path, phase, cause) 异常样本，另计总数。 */
    static final int MAX_INDEX_ERROR_SAMPLES = 32;

    // Per-OS junk never worth indexing. Entries are checked as lower.contains()
    // against the unix-form path, so a leading '/' anchors them to the entry
    // name only — ".trash" also covers ".Trashes"/".Trash-1000", "$windows.~"
    // covers $Windows.~BT/~WS, "icon\r" is the macOS Finder Icon<CR> file.
    private static final List<String> DEFAULT_EXCLUDED_HINTS = Arrays.asList(
            // Windows recycle/system dirs
            "/$recycle.bin/",
            "/$recycle.bin",
            "/system volume information/",
            "/system volume information",
            "/recycler/",
            "/recycler",
            // Windows per-dir junk + huge system files + upgrade leftovers
            "/desktop.ini",
            "/thumbs.db",
            "/$windows.~",
            "/pagefile.sys",
            "/hiberfil.sys",
            "/swapfile.sys",
            // macOS per-volume/per-dir junk (OneDrive .Trash also stalls listings)
            "/.trash",
            "/.ds_store",
            "/.localized",
            "/.fseventsd",
            "/.spotlight-v100",
            "/.documentrevisions-v100",
            "/.temporaryitems",
            "/.vol/",
            "/icon\r"
    );

    private static final Map<String, FailedFileState> FAILED_CONTENT_CACHE = new ConcurrentHashMap<>();

    /**
     * Bounded registry of recent successful mutations: normalized path →
     * System.nanoTime of the write. Lets a queued cleanup verify that the
     * index record it is about to delete is still the same generation —
     * a stale verdict must never delete a doc that was re-indexed after
     * the verdict was formed. Read without holding the write lock.
     */
    private static final int RECENT_UPSERT_CAP = 4096;
    /**
     * Highest generation timestamp ever evicted from {@link #RECENT_UPSERTS}.
     * Purge uses it to tell "no upsert was ever registered" (watermark at or
     * below the classification baseline) from "the in-flight protection was
     * evicted" (watermark above the baseline → the verdict is unverifiable
     * and must be re-checked or deferred, never deleted on sight).
     */
    private static volatile long maxEvictedUpsertNs = Long.MIN_VALUE;
    /**
     * Hard-bounded access-order LRU: size() can NEVER exceed the cap, and
     * put/get are O(1) — no per-upsert table scans. Eviction drops the
     * least-recently-touched generation, which is the least likely to still
     * be inside a purge race window; a stale entry can only cause a skip
     * when its timestamp is genuinely newer than the verdict baseline, so
     * no TTL is needed.
     * 保留 synchronizedMap（不换锁）：有界 4096 LRU 的 get/put/淘汰都是
     * 纯内存 O(1)，不跨任何 I/O，不会阻塞虚拟线程卸载。
     */
    private static final Map<String, Long> RECENT_UPSERTS =
            java.util.Collections.synchronizedMap(
                    new java.util.LinkedHashMap<String, Long>(1024, 0.75f, true) {
                        @Override
                        protected boolean removeEldestEntry(
                                Map.Entry<String, Long> eldest) {
                            if (size() > RECENT_UPSERT_CAP) {
                                if (eldest.getValue() > maxEvictedUpsertNs) {
                                    maxEvictedUpsertNs = eldest.getValue();
                                }
                                return true;
                            }
                            return false;
                        }
                    });

    private static void recordUpsert(String normalizedPath) {
        RECENT_UPSERTS.put(normalizedPath, System.nanoTime());
    }

    /**
     * nanoTime of the last successful upsert/deletion for {@code pathStr}, or -1
     * when unknown. Accepts either stored (index-normalized) or raw OS
     * form — callers hold whichever form their queue carried.
     */
    public static long upsertedAt(String pathStr) {
        if (pathStr == null) {
            return -1L;
        }
        String key = Utils.normalizeForIndex(java.nio.file.Paths.get(pathStr));
        Long t = RECENT_UPSERTS.get(key);
        return t == null ? -1L : t;
    }

    /**
     * Highest upsert generation ever evicted from the bounded registry.
     * A value above a purge classification baseline means evidence for a
     * post-baseline upsert may have been lost — the caller must not treat
     * a {@link #upsertedAt} miss as "no newer write" in that window.
     */
    public static long maxEvictedUpsertNs() {
        return maxEvictedUpsertNs;
    }

    /** Missing LRU evidence is conservative only for verdicts older than its eviction. */
    public static boolean hasNewerUpsert(String path, long baseline) {
        String key = Utils.normalizeForIndex(java.nio.file.Paths.get(path));
        // 保留 monitor：必须原子读取已知代次与淘汰水位，锁内仅查表/比较的
        // 纯内存操作，无 I/O。
        synchronized (RECENT_UPSERTS) {
            Long known = RECENT_UPSERTS.get(key);
            return known != null ? known > baseline : maxEvictedUpsertNs > baseline;
        }
    }

    /* ----------------- 锁外预构建 / 锁内代次保护应用 ----------------- */

    /**
     * A document fully prepared OUTSIDE the shared write lock — attrs read
     * and content extraction already done — carrying the nanoTime baseline
     * captured BEFORE any file I/O. {@link #applyPrepared} refuses it when
     * a newer upsert landed meanwhile, so a stale prepare can never
     * clobber a fresher record.
     */
    public static final class PreparedUpsert {
        final String pathKey;
        final Document document;
        final long preparedAt;

        private PreparedUpsert(String pathKey, Document document, long preparedAt) {
            this.pathKey = pathKey;
            this.document = document;
            this.preparedAt = preparedAt;
        }
    }

    /**
     * Whether {@code path} is lexically eligible for the given index dir.
     * This deliberately does not stat the source. Safe to call from
     * scan/diff phases before committing to a prepare.
     */
    public static boolean shouldIndexPath(Path path, Path indexDir, IndexSettings settings) {
        if (path == null) {
            return false;
        }
        // Exclude the app's own data dir only — never the whole working dir:
        // user.dir may be / or ~ (mac double-click, launched from home) and
        // would exclude every source beneath it (rebuild flashes, empty index).
        return !isExcluded(path, indexDir, settings, AppPaths.getBaseDir());
    }

    /**
     * Whether a file whose content extraction previously failed should be
     * retried now — the size/mtime changed, or the failed-entry TTL window
     * expired. False when there is no recorded failure (nothing to retry)
     * or the identical file is still inside the skip window.
     */
    public static boolean contentRetryDue(Path path, long size, long mtime) {
        if (path == null) {
            return false;
        }
        FailedFileState failed = FAILED_CONTENT_CACHE.get(toPathKey(path));
        if (failed == null) {
            return false;
        }
        if (failed.size != size || failed.mtime != mtime) {
            return true;
        }
        return System.currentTimeMillis() - failed.recordedAt > FAILED_RETRY_TTL_MS;
    }

    /**
     * Builds the index document for {@code path} WITHOUT the write lock —
     * the expensive part (attrs read, Tika extraction) happens here so the
     * shared writer is held only for the {@link #applyPrepared} term swap.
     * Returns {@code null} when the path is excluded or of a type never
     * indexed — callers then converge to a delete. Unknown source I/O
     * propagates without interpreting a timeout or denied read as deletion.
     */
    public static PreparedUpsert prepareUpsert(Path path, Path indexDir, IndexSettings settings)
            throws IOException {
        if (path == null) {
            return null;
        }
        // Baseline BEFORE the file I/O: any upsert during preparation is
        // newer than this prepare and must supersede it at apply time.
        long preparedAt = System.nanoTime();
        if (!shouldIndexPath(path, indexDir, settings)) return null;
        BasicFileAttributes attrs = sourceAttributes(path);
        Document doc = attrs.isDirectory()
                ? buildDirDoc(path, attrs)
                : buildFileDoc(path, attrs, settings);
        if (doc == null) {
            return null;
        }
        return new PreparedUpsert(Utils.normalizeForIndex(path), doc, preparedAt);
    }

    /**
     * Applies a prepared document under the write coordinator. Returns
     * false when an upsert newer than the preparation baseline already
     * landed — the caller must abort the round rather than let a stale
     * prepare overwrite fresher index state.
     */
    public boolean applyPrepared(PreparedUpsert prepared) throws IOException {
        if (prepared == null) {
            return false;
        }
        if (hasNewerUpsert(prepared.pathKey, prepared.preparedAt)) {
            return false;
        }
        try {
            writer.updateDocument(new Term("path", prepared.pathKey), prepared.document);
            noteRestored(prepared.pathKey);
            recordUpsert(prepared.pathKey);
            return true;
        } catch (IOException | RuntimeException e) {
            abortOnClose = true;
            throw e;
        }
    }

    /**
     * Deletes a path under the write coordinator and records its tombstone.
     * Returns false only on a real write failure — the caller treats it as
     * a superseded/failed change and aborts the round.
     */
    public boolean tryDeletePath(Path path) {
        if (path == null) {
            return true;
        }
        try {
            String pathStr = Utils.normalizeForIndex(path);
            // Recovery evidence must exist before a deletion can reach a durable main commit.
            if (shouldIndexPath(path, indexDir, settings)) Tombstones.record(pathStr);
            restoredLock.lock();
            try {
                // 与并行任务的 noteRestored 共用 restoredLock；锁内只有集合操作。
                pendingRestored.remove(pathStr);
            } finally {
                restoredLock.unlock();
            }
            writer.deleteDocuments(new Term("path", pathStr));
            // A prepared read older than this deletion must not resurrect it.
            recordUpsert(pathStr);
            return true;
        } catch (Throwable t) {
            abortOnClose = true;
            log.warn("Delete path error: {}, exception: {}", path, t.getMessage());
            return false;
        }
    }

    /**
     * A failed parse is skipped only within this window — after it the file
     * is retried on the next upsert, so a transient stall (slow share,
     * saturated pool) cannot permanently strand a file without content.
     */
    private static final long FAILED_RETRY_TTL_MS = 10L * 60 * 1000;

    /**
     * Extraction-stall circuit breaker: consecutive parse-future timeouts
     * (the uninterruptible kind — a native read parked on a stalled cloud
     * file-provider; fast OS-level failures do not count). Once tripped,
     * content extraction is skipped for the rest of the run so a
     * placeholder storm costs O(1) per file instead of parseTimeoutSec.
     * Name/path fields keep indexing, so the files stay searchable.
     */
    private static final int EXTRACT_TIMEOUT_BREAKER_LIMIT = 12;
    // 并发完成顺序下统一由一把静态 ReentrantLock 保护 reset、结果事件更新
    // 与熔断准入检查；锁内只有字段操作，helper I/O 与日志都在锁外。
    private static final ReentrantLock EXTRACT_STATE_LOCK = new ReentrantLock();
    private static int consecutiveExtractTimeouts;
    private static boolean extractionBreakerOpen;

    private static class FailedFileState {
        final long size;
        final long mtime;
        final long recordedAt;

        FailedFileState(long size, long mtime) {
            this.size = size;
            this.mtime = mtime;
            this.recordedAt = System.currentTimeMillis();
        }
    }

    /** 索引目录、配置 */
    private final Path indexDir;
    private final IndexSettings settings;
    private final Path appDataDir;

    /** 按字段选择分析器 */
    private final Analyzer perFieldAnalyzer;

    /** 共享的 IndexWriter 实例 */
    private final IndexWriter writer;
    private final FSDirectory directory;
    private final Set<String> pendingRestored = new HashSet<>();
    private static final int RESTORED_BATCH_LIMIT = 256;
    // 并行任务会同时到达 noteRestored/tryDeletePath；恢复集合的 contains/
    // size/add/remove 需要统一互斥，但 Tombstones.contains 的 Lucene I/O
    // 必须留在锁外。
    private final ReentrantLock restoredLock = new ReentrantLock();
    private final BatchOptions batchOptions;
    private volatile boolean abortOnClose;
    private boolean closed;

    private void noteRestored(String path) {
        // Extra evidence is safe: a later bounded resurrection pass clears it.
        restoredLock.lock();
        try {
            if (pendingRestored.size() >= RESTORED_BATCH_LIMIT) return;
        } finally {
            restoredLock.unlock();
        }
        // Tombstones.contains 打开 reader 做 I/O，绝不能包在锁内。
        boolean tombstoned = Tombstones.contains(path);
        restoredLock.lock();
        try {
            if (tombstoned && pendingRestored.size() < RESTORED_BATCH_LIMIT) pendingRestored.add(path);
        } finally {
            restoredLock.unlock();
        }
    }

    public LuceneIndexer(Path indexDir, IndexSettings settings) throws IOException {
        this(indexDir, settings, new BatchOptions());
    }

    /** 测试接缝：包内注入批处理调度/许可/观察者/提取函数；生产默认 PARALLEL/8/真实执行器。 */
    LuceneIndexer(Path indexDir, IndexSettings settings, BatchOptions batchOptions) throws IOException {
        this.batchOptions = batchOptions;
        this.indexDir = indexDir;
        this.settings = settings;
        this.appDataDir = AppPaths.getBaseDir();

        Analyzer std = new StandardAnalyzer();
        Analyzer zh = Analyzers.chinese();
        Analyzer ja = Analyzers.japanese();

        Map<String, Analyzer> perField = new HashMap<>();
        perField.put("name", std);
        perField.put("content", std);
        perField.put("content_zh", zh);
        perField.put("content_ja", ja);

        this.perFieldAnalyzer = new PerFieldAnalyzerWrapper(std, perField);

        Files.createDirectories(indexDir);
        TieredMergePolicy mergePolicy = new TieredMergePolicy();
        mergePolicy.setMaxMergedSegmentMB(512);
        mergePolicy.setSegmentsPerTier(10);
        IndexWriterConfig cfg = new IndexWriterConfig(perFieldAnalyzer)
                .setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND)
                .setRAMBufferSizeMB(32)
                .setMergePolicy(mergePolicy);
        FSDirectory opened = FSDirectory.open(indexDir);
        try {
            this.writer = new IndexWriter(opened, cfg);
            this.directory = opened;
        } catch (IOException | RuntimeException e) {
            opened.close();
            perFieldAnalyzer.close();
            throw e;
        }
    }

    /* --------------------------- 增/改/删 --------------------------- */

    /**
     * 单文件 upsert（新增或修改）；删除须由确认缺失的路径单独提交。
     */
    public void upsertFile(Path file) {
        try {
            tryUpsertFile(file);
        } catch (IOException | RuntimeException e) {
            log.warn("Upsert file error: {}, exception: {}", file, e.getMessage());
        }
    }

    /** Strict callers retain recovery evidence and retry when preparation fails. */
    public boolean tryUpsertFile(Path file) throws IOException {
        if (file == null) return false;
        PreparedUpsert prepared = prepareUpsert(file, indexDir, settings);
        return prepared == null ? tryDeletePath(file) : applyPrepared(prepared);
    }

    /**
     * 删除一个绝对路径对应的文档（按规范化 path 匹配）。
     */
    public void deletePath(Path file) {
        tryDeletePath(file);
    }

    /** Recovery deletions are durable before main commit; restorations clear only afterwards. */
    public void commit() throws IOException {
        if (abortOnClose) throw new IOException("Index transaction was aborted");
        try {
            Tombstones.flush();
            writer.commit();
        } catch (IOException | RuntimeException e) {
            abortOnClose = true;
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException("Recovery registry commit failed", e);
        }
        // A registry failure here can leave redundant recovery work, never lose the main document.
        // commit 在所有批处理任务排空后才执行；集合快照/清理与并发
        // noteRestored 共用 restoredLock。
        List<String> restored;
        restoredLock.lock();
        try {
            restored = new ArrayList<>(pendingRestored);
        } finally {
            restoredLock.unlock();
        }
        try {
            Tombstones.removeBatch(restored);
            Tombstones.flush();
            restoredLock.lock();
            try {
                pendingRestored.removeAll(restored);
            } finally {
                restoredLock.unlock();
            }
        } catch (RuntimeException e) {
            throw new IOException("Could not acknowledge restored documents", e);
        }
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        IOException failure = null;
        try {
            if (!writer.isOpen()) {
                // A caller/test may already have rolled back this transaction.
            } else if (abortOnClose) writer.rollback();
            else {
                try { commit(); }
                catch (IOException e) { failure = e; }
                if (abortOnClose) writer.rollback();
                else writer.close();
            }
        } catch (IOException e) {
            if (failure == null) failure = e; else failure.addSuppressed(e);
        } finally {
            perFieldAnalyzer.close();
            directory.close();
        }
        if (failure != null) throw failure;
    }

    /* --------------------------- 批量索引 --------------------------- */

    /**
     * 全量索引一个根目录。
     */
    public int indexFolder(Path root) throws IOException {
        return indexFolders(root == null ? null : java.util.Collections.singletonList(root), false);
    }

    /** 多目录全量索引；full=true 在同一事务内 deleteAll，成功后一次提交。 */
    public int indexFolders(List<Path> roots, boolean full) throws IOException {
        BatchIndexSession session = null;
        try {
            // deleteAll 与会话分配也属于事务：初始化失败不能让 close 提交空索引。
            resetExtractionRunState();
            if (full) writer.deleteAll();
            session = new BatchIndexSession();
            if (roots != null) {
                List<Path> valid = new ArrayList<>();
                for (Path root : roots) {
                    if (root != null) valid.add(root);
                }
                // 重叠 source 必须等前批真实退出，避免先准备的旧正文覆盖后批。
                int i = 0;
                while (i < valid.size() && !session.cancelled.get()) {
                    List<Path> batch = new ArrayList<>();
                    List<String> norms = new ArrayList<>();
                    Path first = valid.get(i++);
                    batch.add(first);
                    String firstNorm = normalizedOrNull(first);
                    if (firstNorm != null) norms.add(firstNorm);
                    while (batch.size() < MAX_SOURCE_WALKERS && i < valid.size()) {
                        Path candidate = valid.get(i);
                        String candidateNorm = normalizedOrNull(candidate);
                        if (candidateNorm == null || overlapsAny(candidateNorm, norms)) break;
                        batch.add(candidate);
                        norms.add(candidateNorm);
                        i++;
                    }
                    runBatch(session, batch);
                }
            }
            session.finishDrain();
            commit();
            if (full) Tombstones.resetForRebuild();
            return session.visitedCount.get();
        } catch (IOException | RuntimeException | Error failure) {
            abortOnClose = true;
            if (session != null) {
                session.cancel();
                // shutdownNow 不能证明任务退出；writer 的 close/rollback 必须在屏障之后。
                session.drain();
                Error fatal = session.reportOwnerFailure(failure);
                if (fatal != null && fatal != failure) {
                    fatal.addSuppressed(failure);
                    throw fatal;
                }
            }
            throw failure;
        } finally {
            if (session != null) session.close();
        }
    }

    private static String normalizedOrNull(Path root) {
        try {
            return PathScope.normalizeIndexedPath(root);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean overlapsAny(String normalized, List<String> existing) {
        for (String other : existing) {
            if (normalized.equals(other)) return true;
            String prefix = PathScope.descendantPrefix(other);
            String own = PathScope.descendantPrefix(normalized);
            if ((prefix != null && normalized.startsWith(prefix))
                    || (own != null && other.startsWith(own))) return true;
        }
        return false;
    }

    /** 提交一批（≤2）source 任务并等其排空后才返回；中断时取消并继续排空。 */
    private void runBatch(BatchIndexSession session, List<Path> batch) throws IOException {
        List<BatchSource> sources = new ArrayList<>(batch.size());
        for (Path root : batch) {
            if (session.cancelled.get()) break;
            BatchSource source = session.submitSource(root);
            if (source != null) sources.add(source);
        }
        for (BatchSource source : sources) {
            session.awaitSource(source);
        }
    }

    /** Hard recursion bound — a pathological nesting depth cannot blow the stack. */
    private static final int MAX_WALK_DEPTH = 512;

    private void walkOneRoot(BatchIndexSession session, BatchSource source) {
        Path root = source.root;
        try {
            if (!Files.exists(root)) {
                log.warn("Root path does not exist: {}", root);
                session.onError(root, IndexErrorSink.Phase.WALK, new java.nio.file.NoSuchFileException(root.toString()));
                return;
            }
            // Recursive walk rather than Files.walkFileTree: a directory whose
            // listing fails mid-iteration (cloud file-provider placeholders
            // like OneDrive .Trash, permission errors, vanished dirs) must only
            // lose its own subtree, not abort the whole root — walkFileTree
            // propagates such failures and ends the scan. Links are followed,
            // so a symlinked source root (~/OneDrive on macOS) is walked
            // instead of indexed as a single file record.
            walkDir(session, source, root);
        } catch (Throwable t) {
            log.error("Walk tree error for root {}: {}", root, t.getMessage());
            session.onError(root, IndexErrorSink.Phase.WALK, t);
        }
    }

    private void walkDir(BatchIndexSession session, BatchSource source, Path path) {
        try {
            if (isExcluded(path)) {
                return;
            }
            BasicFileAttributes attrs;
            try {
                // Default readAttributes follows links — a symlinked dir is
                // treated as a dir; a dangling link fails here and is skipped.
                attrs = Files.readAttributes(path, BasicFileAttributes.class);
            } catch (IOException e) {
                log.warn("Visit path failed: {}, exception: {}", path, e.getMessage());
                session.onError(path, IndexErrorSink.Phase.WALK, e);
                return;
            }
            if (!attrs.isDirectory()) {
                session.dispatchFile(source, path, attrs);
                return;
            }
            session.dispatchDirectory(source, path, attrs);
            if (source.ancestry.size() >= MAX_WALK_DEPTH) {
                log.warn("Max directory depth reached, skipping subtree: {}", path);
                return;
            }
            // Link-cycle detection across the current ancestor chain; fileKey
            // (dev+inode) is free and needs no extra real-path resolution.
            // ancestry 属于单个 source 线程，禁止跨线程共享或修改。
            Object key = attrs.fileKey();
            if (key != null && !source.ancestry.add(key)) {
                return;
            }
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
                for (Path child : stream) {
                    if (session.cancelled.get()) return;
                    walkDir(session, source, child);
                }
            } catch (IOException | DirectoryIteratorException e) {
                // Scoped to this directory only — siblings keep walking.
                log.warn("Directory listing failed: {}, exception: {}", path, e.getMessage());
                session.onError(path, IndexErrorSink.Phase.WALK, e);
            } finally {
                if (key != null) {
                    source.ancestry.remove(key);
                }
            }
        } catch (Throwable t) {
            log.warn("Walk directory error: {}, exception: {}", path, t.getMessage());
            session.onError(path, IndexErrorSink.Phase.WALK, t);
        }
    }

    /* --------------------------- 核心索引逻辑 --------------------------- */

    /** 批处理文件动作：与原 indexFile 相同的构建/更新/恢复登记顺序，
     *  失败仍置 abortOnClose 并按 WRITE 阶段上报 session。 */
    private void indexFileInBatch(BatchIndexSession session, Path file, BasicFileAttributes attrs) {
        try {
            session.observer().beforeBuild(file);
            Document doc = buildFileDoc(file, attrs, settings, session, session.extractor());
            if (doc == null) return;
            String pathStr = Utils.normalizeForIndex(file);
            session.observer().beforeUpdate(file);
            writer.updateDocument(new Term("path", pathStr), doc);
            noteRestored(pathStr);
            recordUpsert(pathStr);
        } catch (Throwable t) {
            abortOnClose = true;
            session.onError(file, IndexErrorSink.Phase.WRITE, t);
            log.warn("Index file error: {}, exception: {}", file, t.getMessage());
        }
    }

    /** 批处理目录动作：字段与原 indexDirectory 完全一致。 */
    private void indexDirectoryInBatch(BatchIndexSession session, Path dir, BasicFileAttributes attrs) {
        try {
            String pathStr = Utils.normalizeForIndex(dir);
            session.observer().beforeBuild(dir);
            Document doc = buildDirDoc(dir, attrs);
            session.observer().beforeUpdate(dir);
            writer.updateDocument(new Term("path", pathStr), doc);
            noteRestored(pathStr);
            recordUpsert(pathStr);
        } catch (Throwable t) {
            abortOnClose = true;
            session.onError(dir, IndexErrorSink.Phase.WRITE, t);
            log.warn("Index directory error: {}, exception: {}", dir, t.getMessage());
        }
    }

    /** Document construction WITHOUT a writer — the same fields the write
     *  path adds, built outside the shared lock by prepareUpsert. */
    private static Document buildDirDoc(Path dir, BasicFileAttributes attrs) {
        String pathStr = Utils.normalizeForIndex(dir);
        String name = dir.getFileName() == null ? dir.toString() : dir.getFileName().toString();

        Document doc = new Document();
        doc.add(new StringField("path", pathStr, Field.Store.YES));
        doc.add(new StringField("path_scope", PathScope.normalizeIndexedPath(dir), Field.Store.NO));
        doc.add(new TextField("name", name, Field.Store.YES));
        doc.add(new StringField("name_raw", name.toLowerCase(Locale.ROOT), Field.Store.NO));
        doc.add(new StringField("ext", "", Field.Store.YES));
        doc.add(new StringField("kind", KIND_FOLDER, Field.Store.YES));
        doc.add(new LongPoint("mtime_l", attrs.lastModifiedTime().toMillis()));
        doc.add(new StoredField("mtime", attrs.lastModifiedTime().toMillis()));
        doc.add(new StoredField("size", 0L));
        doc.add(new StoredField("ctime", attrs.creationTime().toMillis()));
        doc.add(new StoredField("atime", attrs.lastAccessTime().toMillis()));
        return doc;
    }

    /** Document construction WITHOUT a writer, including content
     *  extraction — null when the file type is never indexed. */
    private static Document buildFileDoc(Path file, BasicFileAttributes attrs, IndexSettings settings) {
        // 增量调用不携带批处理会话：no-op sink + 默认提取函数。
        return buildFileDoc(file, attrs, settings, IndexErrorSink.NOOP, IsolatedFileAccess::extract);
    }

    private static Document buildFileDoc(Path file, BasicFileAttributes attrs, IndexSettings settings,
                                         IndexErrorSink sink, ExtractFn extractor) {
        String name = file.getFileName() == null ? file.toString() : file.getFileName().toString();
        // 排除二进制执行文件
        if (name.endsWith(".exe") || name.endsWith(".dll")) return null;

        String pathStr = Utils.normalizeForIndex(file);

        Document doc = new Document();
        doc.add(new StringField("path", pathStr, Field.Store.YES));
        doc.add(new StringField("path_scope", PathScope.normalizeIndexedPath(file), Field.Store.NO));
        doc.add(new TextField("name", name, Field.Store.YES));
        doc.add(new StringField("name_raw", name.toLowerCase(Locale.ROOT), Field.Store.NO));
        doc.add(new StringField("ext", getExt(name), Field.Store.YES));
        doc.add(new StringField("kind", KIND_FILE, Field.Store.YES));
        doc.add(new LongPoint("mtime_l", attrs.lastModifiedTime().toMillis()));
        doc.add(new StoredField("mtime", attrs.lastModifiedTime().toMillis()));
        doc.add(new StoredField("size", attrs.size()));
        doc.add(new StoredField("ctime", attrs.creationTime().toMillis()));
        doc.add(new StoredField("atime", attrs.lastAccessTime().toMillis()));

        String mime = null;
        String content = "";
        try {
            if (shouldParseContent(file, name, attrs.size(), settings)
                    && extractionAdmissionOpen()
                    && !shouldSkipContentExtraction(file, attrs)) {
                IsolatedFileAccess.Extraction extracted = extractTextReadOnly(file, attrs, settings, sink, extractor);
                if (extracted != null) {
                    content = extracted.getText();
                    mime = extracted.getMimeType();
                }
            }
        } catch (Throwable e) {
            log.warn("Get mime/content error: {}, exception: {}", file, e.getMessage());
            sink.onError(file, IndexErrorSink.Phase.EXTRACT, e);
        }

        if (mime != null) doc.add(new StringField("mime", mime, Field.Store.YES));
        if (!content.isEmpty()) {
            doc.add(new TextField("content", content, Field.Store.NO));
            doc.add(new TextField("content_zh", content, Field.Store.NO));
            doc.add(new TextField("content_ja", content, Field.Store.NO));
        }
        return doc;
    }

    /* --------------------------- 规则/工具 --------------------------- */

    private boolean isExcluded(Path p) {
        return isExcluded(p, indexDir, settings, appDataDir);
    }

    /** Static form — the prepare path has no indexer instance. */
    private static boolean isExcluded(Path p, Path indexDir, IndexSettings settings, Path appDataDir) {
        try {
            String unix = p.toString().replace('\\', '/');
            String lower = unix.toLowerCase(Locale.ROOT);
            if (p.toAbsolutePath().normalize().startsWith(indexDir.toAbsolutePath().normalize())) return true;
            if (p.toAbsolutePath().normalize().startsWith(appDataDir)) return true;
            for (String hint : DEFAULT_EXCLUDED_HINTS) {
                if (lower.contains(hint)) return true;
            }
            String name = p.getFileName() == null ? "" : p.getFileName().toString();
            String lowerName = name.toLowerCase(Locale.ROOT);
            if (lowerName.startsWith("~$") || lowerName.startsWith("~") || lowerName.endsWith(".tmp") || lowerName.endsWith(".temp")) {
                return true;
            }
            if (settings.excludeGlob == null) return false;
            for (String g : settings.excludeGlob) {
                try {
                    PathMatcher m = FileSystems.getDefault().getPathMatcher("glob:" + g);
                    if (m.matches(p)) return true;
                } catch (Throwable e) {
                    log.warn("Invalid glob pattern: {}, exception: {}", g, e.getMessage());
                }
                // 兜底判断
                String hint = g.replace("**/", "").replace("/**", "");
                if (!hint.isEmpty() && unix.contains(hint)) return true;
            }
        } catch (Throwable t) {
            log.warn("isExcluded check error: {}, exception: {}", p, t.getMessage());
        }
        return false;
    }

    private static String getExt(String name) {
        int i = name.lastIndexOf('.');
        return (i > 0) ? name.substring(i + 1).toLowerCase(Locale.ROOT) : "";
    }

    /** 熔断准入检查：只有 EXTRACT_STATE_LOCK 内的字段读取，无 I/O。 */
    private static boolean extractionAdmissionOpen() {
        EXTRACT_STATE_LOCK.lock();
        try {
            return !extractionBreakerOpen;
        } finally {
            EXTRACT_STATE_LOCK.unlock();
        }
    }

    /** 每次 indexFolder/indexFolders 调用在分发前统一重置连续超时状态，
     *  禁止每个 source worker 各自重置。 */
    private static void resetExtractionRunState() {
        EXTRACT_STATE_LOCK.lock();
        try {
            consecutiveExtractTimeouts = 0;
            extractionBreakerOpen = false;
        } finally {
            EXTRACT_STATE_LOCK.unlock();
        }
    }

    /** 测试接缝：清空失败缓存与熔断状态，保证用例间不互相污染。 */
    static void resetExtractionStateForTests() {
        FAILED_CONTENT_CACHE.clear();
        resetExtractionRunState();
    }

    private static IsolatedFileAccess.Extraction extractTextReadOnly(Path file, BasicFileAttributes attrs,
                                                                     IndexSettings settings, IndexErrorSink sink,
                                                                     ExtractFn extractor) {
        try {
            int maxChars = settings.maxExtractChars > 0 ? Math.min(settings.maxExtractChars, DEFAULT_MAX_CHARS) : DEFAULT_MAX_CHARS;
            long timeout = Math.max(1L, settings.parseTimeoutSec) * 1000L;
            IsolatedFileAccess.Extraction extracted = extractor.extract(file, maxChars, (int) Math.min(Integer.MAX_VALUE, timeout));
            // 一次成功返回（即使 isSuccessful=false）仍重置连续超时计数；
            // 完成结果不关闭已打开的熔断器。
            EXTRACT_STATE_LOCK.lock();
            try {
                consecutiveExtractTimeouts = 0;
            } finally {
                EXTRACT_STATE_LOCK.unlock();
            }
            if (extracted.isSuccessful()) clearFailedExtraction(file);
            else {
                rememberFailedExtraction(file, attrs);
                sink.onError(file, IndexErrorSink.Phase.EXTRACT,
                        new IOException("Extraction reported unsuccessful"));
            }
            return extracted;
        } catch (IOException e) {
            // 只有 TimeoutException cause 计入连续超时；其他 IOException 重置。
            EXTRACT_STATE_LOCK.lock();
            try {
                if (e.getCause() instanceof java.util.concurrent.TimeoutException) {
                    if (++consecutiveExtractTimeouts >= EXTRACT_TIMEOUT_BREAKER_LIMIT) {
                        extractionBreakerOpen = true;
                    }
                } else {
                    consecutiveExtractTimeouts = 0;
                }
            } finally {
                EXTRACT_STATE_LOCK.unlock();
            }
            rememberFailedExtraction(file, attrs);
            sink.onError(file, IndexErrorSink.Phase.EXTRACT, e);
            log.warn("Isolated extraction failed for {}: {}", file, e.getMessage());
            return null;
        }
    }

    private static BasicFileAttributes sourceAttributes(Path path) throws IOException {
        final IsolatedFileAccess.Attributes attrs = IsolatedFileAccess.attributes(path, 5000);
        return new BasicFileAttributes() {
            public java.nio.file.attribute.FileTime lastModifiedTime() { return java.nio.file.attribute.FileTime.fromMillis(attrs.getLastModifiedTime()); }
            public java.nio.file.attribute.FileTime lastAccessTime() { return java.nio.file.attribute.FileTime.fromMillis(attrs.getLastAccessTime()); }
            public java.nio.file.attribute.FileTime creationTime() { return java.nio.file.attribute.FileTime.fromMillis(attrs.getCreationTime()); }
            public boolean isRegularFile() { return attrs.isRegularFile(); }
            public boolean isDirectory() { return attrs.isDirectory(); }
            public boolean isSymbolicLink() { return false; }
            public boolean isOther() { return !isRegularFile() && !isDirectory(); }
            public long size() { return attrs.getSize(); }
            public Object fileKey() { return null; }
        };
    }

    private static boolean shouldSkipContentExtraction(Path file, BasicFileAttributes attrs) {
        String key = toPathKey(file);
        FailedFileState failed = FAILED_CONTENT_CACHE.get(key);
        if (failed == null) {
            return false;
        }
        long size = attrs.size();
        long mtime = attrs.lastModifiedTime().toMillis();
        if (System.currentTimeMillis() - failed.recordedAt > FAILED_RETRY_TTL_MS) {
            FAILED_CONTENT_CACHE.remove(key);
            return false; // retry window expired — parse again
        }
        return failed.size == size && failed.mtime == mtime;
    }

    private static void rememberFailedExtraction(Path file, BasicFileAttributes attrs) {
        if (attrs == null) {
            return;
        }
        if (FAILED_CONTENT_CACHE.size() > MAX_ERROR_CACHE_SIZE) {
            FAILED_CONTENT_CACHE.clear();
        }
        FAILED_CONTENT_CACHE.put(toPathKey(file), new FailedFileState(attrs.size(), attrs.lastModifiedTime().toMillis()));
    }

    private static void clearFailedExtraction(Path file) {
        FAILED_CONTENT_CACHE.remove(toPathKey(file));
    }

    private static String toPathKey(Path file) {
        return Utils.normalizeForIndex(file);
    }

    private static boolean shouldParseContent(Path file, String name, long sizeBytes, IndexSettings settings) {
        if (sizeBytes <= 0 || sizeBytes > settings.maxFileMB * 1024L * 1024L) return false;
        String ext = getExt(name);
        if (settings.includeExt != null && settings.includeExt.contains(ext)) return true;
        if (!settings.parseTextLike) return false;

        long textLimitBytes = settings.textMaxBytes > 0 ? settings.textMaxBytes : settings.maxFileMB * 1024L * 1024L;
        if (sizeBytes > textLimitBytes) {
            return false;
        }

        if (settings.textExts != null && settings.textExts.contains(ext)) return true;
        try {
            return IsolatedFileAccess.isTextLike(file, 1000);
        } catch (IOException e) {
            return false;
        }
    }

    /* --------------------------- 批处理会话（§3） --------------------------- */

    /** 批处理调度：生产固定 PARALLEL；SERIAL 在遍历线程内联执行同一动作。 */
    enum Scheduling { SERIAL, PARALLEL }

    /** 提取函数接缝：批处理可注入可控实现替代 IsolatedFileAccess.extract。 */
    interface ExtractFn {
        IsolatedFileAccess.Extraction extract(Path path, int maxChars, int timeoutMs) throws IOException;
    }

    /** 错误分类事件：sink 不得抛异常、改变返回值或做 I/O。 */
    interface IndexErrorSink {
        enum Phase { WALK, EXTRACT, WRITE }
        IndexErrorSink NOOP = (path, phase, cause) -> { };
        void onError(Path path, Phase phase, Throwable cause);
    }

    /** 批处理观察接缝：所有回调不得在我们持有的任何锁/monitor 内执行。 */
    interface BatchObserver {
        BatchObserver NOOP = new BatchObserver() { };
        /** 遍历线程上、取许可之前的分发尝试。 */
        default void onDispatchAttempt(Path path, boolean directory) { }
        /** 许可取得之后、phaser 登记之前；不持有内部锁。 */
        default void onPermitAcquired(Path path) { }
        /** 许可+phaser 登记完成、executor.execute 之前。 */
        default void onTaskCreated(Path path) { }
        /** 任务内、runningThreads 登记之后。 */
        default void onTaskStarted(Path path) { }
        /** 任务内、文档构建之前；可抛出注入构建失败。 */
        default void beforeBuild(Path path) { }
        /** writer.updateDocument 之前；可阻塞或抛出注入写失败。 */
        default void beforeUpdate(Path path) { }
        /** 任务 finally、许可归还/phaser 注销之后。 */
        default void onTaskFinished(Path path) { }
        /** source 提交后；供测试读取 phaser/latch 水位。 */
        default void onSourceSubmitted(BatchSource source) { }
        /** 拥有者线程：全部 source/任务退出、执行器终止后、错误检查前。 */
        default void onBatchDrained() { }
    }

    /** 批处理选项：仅包内测试接缝，不引入任何用户配置键。 */
    static final class BatchOptions {
        Scheduling scheduling = Scheduling.PARALLEL;
        Semaphore permits = INDEX_TASK_PERMITS;
        // 具名每任务虚拟线程执行器；不是固定大小的虚拟线程池。
        Supplier<ExecutorService> executorFactory =
                () -> Executors.newThreadPerTaskExecutor(
                        Thread.ofVirtual().name("docfinder-index-", 0).factory());
        BatchObserver observer = BatchObserver.NOOP;
        ExtractFn extractor = IsolatedFileAccess::extract;
    }

    /** 单个 source 的运行状态：遍历线程独占 ancestry，phaser/latch 作完成屏障。 */
    static final class BatchSource {
        final Path root;
        // phaser(1) 的初始 party 归 source 自身；文件/目录任务 register 后
        // 在 finally arriveAndDeregister，source 末尾 arriveAndAwaitAdvance
        // 形成真实完成屏障（不消耗索引许可，source 本身也不持有许可）。
        final Phaser phaser = new Phaser(1);
        final CountDownLatch done = new CountDownLatch(1);
        final Set<Object> ancestry = new HashSet<>();
        BatchSource(Path root) { this.root = root; }
        int registeredParties() { return phaser.getRegisteredParties(); }
        long pendingDone() { return done.getCount(); }
    }

    /**
     * 一次 indexFolder/indexFolders 调用创建并关闭的批处理会话：
     * 持有取消标记、访问计数、按阶段分类的错误计数与 ≤32 个样本、
     * 正在执行的线程集合与（PARALLEL 下）具名虚拟线程执行器。
     */
    final class BatchIndexSession implements IndexErrorSink {
        final BatchOptions options = batchOptions;
        final AtomicBoolean cancelled = new AtomicBoolean();
        final AtomicInteger visitedCount = new AtomicInteger();
        private final ReentrantLock errorLock = new ReentrantLock();
        private final int[] errorCounts = new int[IndexErrorSink.Phase.values().length];
        private final List<ErrorSample> samples = new ArrayList<>();
        private Throwable firstFailure;
        private Error fatalError;
        private boolean ownerInterrupted;
        private boolean drained;
        private ErrorSummary summary;
        private boolean failureReported;
        private final Set<Thread> runningThreads = ConcurrentHashMap.newKeySet();
        private final List<BatchSource> sources = new ArrayList<>();
        final ExecutorService executor;

        BatchIndexSession() {
            // 所有可能分配失败的内存状态先初始化；最后取得执行器后只赋值，
            // 避免构造中途失败把已经创建的执行器遗留在不可访问的半成品里。
            executor = options.scheduling == Scheduling.PARALLEL
                    ? java.util.Objects.requireNonNull(options.executorFactory.get()) : null;
        }

        BatchObserver observer() { return options.observer; }
        ExtractFn extractor() { return options.extractor; }

        private static final class ErrorSample {
            final Path path;
            final IndexErrorSink.Phase phase;
            final Throwable cause;
            ErrorSample(Path path, IndexErrorSink.Phase phase, Throwable cause) {
                this.path = path;
                this.phase = phase;
                this.cause = cause;
            }
            @Override public String toString() { return phase + " at " + path + ": " + cause; }
        }

        private record ErrorSummary(Throwable failure, Error fatal, String totals, List<ErrorSample> samples) {
            Throwable firstCause() {
                return failure != null ? failure : samples.isEmpty() ? null : samples.getFirst().cause;
            }

            IOException exception(Throwable primary) {
                Throwable first = firstCause();
                IOException context = new IOException("Index transaction was aborted: " + totals
                        + "; samples=" + samples.size() + "; first cause=" + first,
                        first == primary ? null : first);
                for (ErrorSample sample : samples) {
                    if (sample.cause != first && sample.cause != primary
                            && context.getSuppressed().length < MAX_INDEX_ERROR_SAMPLES - 1) {
                        context.addSuppressed(new IOException(sample.phase + " failure at " + sample.path, sample.cause));
                    }
                }
                return context;
            }
        }

        @Override
        public void onError(Path path, IndexErrorSink.Phase phase, Throwable cause) {
            errorLock.lock();
            try {
                errorCounts[phase.ordinal()]++;
                // 先保存不需分配的致命状态，样本分配失败也不能把 Error 降为成功。
                if (cause instanceof Error && fatalError == null) fatalError = (Error) cause;
                if (phase == IndexErrorSink.Phase.WRITE || cause instanceof Error) {
                    cancelled.set(true);
                    if (firstFailure == null) firstFailure = cause;
                    abortOnClose = true;
                }
                if (samples.size() < MAX_INDEX_ERROR_SAMPLES) {
                    samples.add(new ErrorSample(path, phase, cause));
                }
            } finally {
                errorLock.unlock();
            }
        }

        void failFatal(Throwable cause) {
            onError(null, IndexErrorSink.Phase.WRITE, cause);
            cancel();
        }

        void cancel() {
            cancelled.set(true);
            abortOnClose = true;
            for (Thread thread : runningThreads) thread.interrupt();
        }

        BatchSource submitSource(Path root) {
            BatchSource source = null;
            boolean accepted = false;
            try {
                source = new BatchSource(root);
                sources.add(source);
                observer().onSourceSubmitted(source);
                final BatchSource owned = source;
                Runnable walk = () -> {
                    Thread self = Thread.currentThread();
                    try {
                        // 集合登记也可能分配失败，必须处于 source 的完成保护中。
                        runningThreads.add(self);
                        if (!cancelled.get()) walkOneRoot(this, owned);
                    } catch (Throwable failure) {
                        failFatal(failure);
                    } finally {
                        try {
                            runningThreads.remove(self);
                        } finally {
                            try {
                                owned.phaser.arriveAndAwaitAdvance();
                            } finally {
                                owned.done.countDown();
                            }
                        }
                    }
                };
                if (options.scheduling == Scheduling.SERIAL) {
                    accepted = true;
                    walk.run();
                } else {
                    executor.execute(walk);
                    accepted = true;
                }
            } catch (RuntimeException | Error failure) {
                failFatal(failure);
            } finally {
                // executor 尚未接管的所有准备窗口由提交方结算，包含观察回调失败。
                if (!accepted && source != null) {
                    source.phaser.arriveAndDeregister();
                    source.done.countDown();
                }
            }
            return source;
        }

        void dispatchFile(BatchSource source, Path path, BasicFileAttributes attrs) {
            dispatch(source, path, attrs, false);
        }

        void dispatchDirectory(BatchSource source, Path path, BasicFileAttributes attrs) {
            dispatch(source, path, attrs, true);
        }

        private void dispatch(BatchSource source, Path path, BasicFileAttributes attrs, boolean directory) {
            if (cancelled.get()) return;
            boolean parallel = options.scheduling == Scheduling.PARALLEL;
            boolean acquired = false;
            boolean registered = false;
            boolean accepted = false;
            try {
                observer().onDispatchAttempt(path, directory);
                if (parallel) {
                    options.permits.acquire();
                    acquired = true;
                    observer().onPermitAcquired(path);
                }
                if (cancelled.get()) return;
                source.phaser.register();
                registered = true;
                observer().onTaskCreated(path);
                Runnable task = () -> {
                    Thread self = Thread.currentThread();
                    try {
                        runningThreads.add(self);
                        observer().onTaskStarted(path);
                        if (!cancelled.get()) {
                            if (directory) indexDirectoryInBatch(this, path, attrs);
                            else indexFileInBatch(this, path, attrs);
                            visitedCount.incrementAndGet();
                        }
                    } catch (Throwable failure) {
                        onError(path, IndexErrorSink.Phase.WRITE, failure);
                    } finally {
                        try {
                            runningThreads.remove(self);
                        } finally {
                            if (parallel) options.permits.release();
                            source.phaser.arriveAndDeregister();
                        }
                        try {
                            observer().onTaskFinished(path);
                        } catch (Throwable failure) {
                            failFatal(failure);
                        }
                    }
                };
                if (parallel) {
                    executor.execute(task);
                    accepted = true;
                } else {
                    accepted = true;
                    task.run();
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                cancel();
            } catch (RuntimeException | Error failure) {
                failFatal(failure);
            } finally {
                // acquire 成功起显式持有所有权，只有 execute 接受后才交给任务 finally。
                if (!accepted) {
                    if (registered) source.phaser.arriveAndDeregister();
                    if (acquired) options.permits.release();
                }
            }
        }

        void awaitSource(BatchSource source) {
            while (true) {
                try {
                    source.done.await();
                    break;
                } catch (InterruptedException failure) {
                    ownerInterrupted = true;
                    cancel();
                }
            }
        }

        /** 已接受 source 与任务真实退出后才返回；中断仅请求取消，不能缩短屏障。 */
        void drain() {
            if (drained) return;
            for (BatchSource source : sources) awaitSource(source);
            if (executor != null) {
                executor.shutdown();
                while (true) {
                    try {
                        if (executor.awaitTermination(30, TimeUnit.SECONDS)) break;
                    } catch (InterruptedException failure) {
                        ownerInterrupted = true;
                        cancel();
                    }
                }
            }
            drained = true;
        }

        /** 仅在排空之后由拥有者调用：锁内快照、锁外且仅一次报告。 */
        private ErrorSummary reportErrors() {
            if (summary != null) return summary;
            Throwable failure;
            Error fatal;
            int[] counts;
            List<ErrorSample> snapshot;
            errorLock.lock();
            try {
                failure = firstFailure;
                fatal = fatalError;
                counts = errorCounts.clone();
                snapshot = new ArrayList<>(samples);
            } finally {
                errorLock.unlock();
            }
            String totals = "WALK=" + counts[0] + ", EXTRACT=" + counts[1] + ", WRITE=" + counts[2];
            summary = new ErrorSummary(failure, fatal, totals, snapshot);
            if (!snapshot.isEmpty()) {
                // 日志与异常构建都在锁外；总数不受 32 个样本上限影响。
                log.warn("Index batch summary: {}; samples={}; first={}; details={}",
                        totals, snapshot.size(), summary.firstCause(), snapshot);
            }
            return summary;
        }

        Error reportOwnerFailure(Throwable failure) {
            ErrorSummary errors = reportErrors();
            Throwable primary = errors.fatal != null ? errors.fatal : failure;
            if (!failureReported && !errors.samples.isEmpty()) {
                primary.addSuppressed(errors.exception(primary));
                failureReported = true;
            }
            return errors.fatal;
        }

        void finishDrain() throws IOException {
            drain();
            observer().onBatchDrained();
            ErrorSummary errors = reportErrors();
            if (ownerInterrupted) Thread.currentThread().interrupt();
            if (errors.fatal != null) {
                errors.fatal.addSuppressed(errors.exception(errors.fatal));
                failureReported = true;
                throw errors.fatal;
            }
            if (ownerInterrupted) throw new InterruptedIOException("Index batch interrupted: " + errors.totals);
            if (errors.failure != null) {
                IOException io = errors.exception(null);
                failureReported = true;
                throw io;
            }
        }

        void close() {
            if (!drained) {
                cancel();
                drain();
            }
            if (ownerInterrupted) Thread.currentThread().interrupt();
        }
    }

}
