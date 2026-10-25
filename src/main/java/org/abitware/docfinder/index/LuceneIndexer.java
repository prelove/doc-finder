package org.abitware.docfinder.index;

import org.abitware.docfinder.util.AppPaths;
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
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;

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
     * Bounded registry of recent successful upserts: normalized path →
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
    private static volatile long maxEvictedUpsertNs;
    /**
     * Hard-bounded access-order LRU: size() can NEVER exceed the cap, and
     * put/get are O(1) — no per-upsert table scans. Eviction drops the
     * least-recently-touched generation, which is the least likely to still
     * be inside a purge race window; a stale entry can only cause a skip
     * when its timestamp is genuinely newer than the verdict baseline, so
     * no TTL is needed.
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
     * nanoTime of the last successful upsert for {@code pathStr}, or -1
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
     * Whether {@code path} should be indexed for the given index dir:
     * exists on disk and is not excluded. Lock-free — safe to call from
     * scan/diff phases before committing to a prepare.
     */
    public static boolean shouldIndexPath(Path path, Path indexDir, IndexSettings settings) {
        if (path == null) {
            return false;
        }
        try {
            if (!Files.exists(path)) {
                return false;
            }
        } catch (Throwable t) {
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
     * indexed — callers then converge to a delete. {@link NoSuchFileException}
     * propagates: a known absence after CREATE/MODIFY converges to deletion.
     */
    public static PreparedUpsert prepareUpsert(Path path, Path indexDir, IndexSettings settings)
            throws IOException {
        if (path == null) {
            return null;
        }
        // Baseline BEFORE the file I/O: any upsert during preparation is
        // newer than this prepare and must supersede it at apply time.
        long preparedAt = System.nanoTime();
        BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
        if (isExcluded(path, indexDir, settings, AppPaths.getBaseDir())) {
            return null;
        }
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
        if (upsertedAt(prepared.pathKey) > prepared.preparedAt) {
            return false;
        }
        writer.updateDocument(new Term("path", prepared.pathKey), prepared.document);
        Tombstones.remove(prepared.pathKey);
        recordUpsert(prepared.pathKey);
        return true;
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
            writer.deleteDocuments(new Term("path", pathStr));
            Tombstones.record(pathStr);
            return true;
        } catch (Throwable t) {
            log.warn("Delete path error: {}, exception: {}", path, t.getMessage());
            return false;
        }
    }

    /**
     * Shared bounded pool for Tika extraction. At most PARSER_THREADS workers
     * exist for the whole JVM and the submission queue is bounded — a parse
     * that ignores interruption can occupy one slot, but slots never spawn
     * replacements the way per-file single-thread executors did. Idle workers
     * expire so the thread count provably returns to zero after indexing.
     * Saturated pool → the file is recorded as failed and indexed without
     * content instead of queueing unboundedly.
     */
    private static final int PARSER_THREADS = 2;
    private static final int PARSER_QUEUE = 64;
    private static final java.util.concurrent.ThreadPoolExecutor PARSER_POOL =
            new java.util.concurrent.ThreadPoolExecutor(
                    PARSER_THREADS, PARSER_THREADS,
                    60L, TimeUnit.SECONDS,
                    new java.util.concurrent.ArrayBlockingQueue<Runnable>(PARSER_QUEUE),
                    r -> {
                        Thread t = new Thread(r, "tika-extract");
                        t.setDaemon(true);
                        return t;
                    });
    static {
        PARSER_POOL.allowCoreThreadTimeOut(true);
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
    private static final AtomicInteger CONSECUTIVE_EXTRACT_TIMEOUTS = new AtomicInteger(0);
    private static volatile boolean extractionBreakerOpen = false;

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

    /** Tika 解析器（自动探测）— shared: AutoDetectParser is thread-safe and
     *  the static prepare path must parse without an indexer instance. */
    private static final AutoDetectParser tikaParser = new AutoDetectParser();

    /** 索引目录、配置 */
    private final Path indexDir;
    private final IndexSettings settings;
    private final Path appDataDir;

    /** 按字段选择分析器 */
    private final Analyzer perFieldAnalyzer;

    /** 共享的 IndexWriter 实例 */
    private final IndexWriter writer;

    public LuceneIndexer(Path indexDir, IndexSettings settings) throws IOException {
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
                .setRAMBufferSizeMB(256)
                .setMergePolicy(mergePolicy);
        this.writer = new IndexWriter(FSDirectory.open(indexDir), cfg);
    }

    /* --------------------------- 增/改/删 --------------------------- */

    /**
     * 单文件 upsert（新增或修改）；若文件不存在则等价 delete。
     */
    public void upsertFile(Path file) {
        if (file == null) return;
        try {
            if (!Files.exists(file)) {
                deletePath(file);
                return;
            }
            if (isExcluded(file)) {
                deletePath(file);
                return;
            }

            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
            if (attrs.isDirectory()) {
                indexDirectory(this.writer, file, attrs);
            } else {
                indexFile(this.writer, file, attrs);
            }
        } catch (Throwable t) {
            log.warn("Upsert file error: {}, exception: {}", file, t.getMessage());
        }
    }

    /**
     * 删除一个绝对路径对应的文档（按规范化 path 匹配）。
     */
    public void deletePath(Path file) {
        if (file == null) return;
        try {
            String pathStr = Utils.normalizeForIndex(file);
            this.writer.deleteDocuments(new Term("path", pathStr));
            // Every deletion leaves a tombstone so a file that later comes
            // back (restored, remounted) can be resurrected by re-indexing —
            // even when no watcher or poller covers its root.
            Tombstones.record(pathStr);
        } catch (Throwable t) {
            log.warn("Delete path error: {}, exception: {}", file, t.getMessage());
        }
    }

    /**
     * 提交所有挂起的索引更改。
     */
    public void commit() throws IOException {
        writer.commit();
    }

    @Override
    public void close() throws IOException {
        if (writer != null) {
            writer.close();
        }
        if (perFieldAnalyzer != null) {
            perFieldAnalyzer.close();
        }
    }

    /* --------------------------- 批量索引 --------------------------- */

    /**
     * 全量索引一个根目录。
     */
    public int indexFolder(Path root) throws IOException {
        CONSECUTIVE_EXTRACT_TIMEOUTS.set(0);
        extractionBreakerOpen = false;
        AtomicInteger count = new AtomicInteger(0);
        walkOneRoot(this.writer, root, count);
        writer.commit();
        return count.get();
    }

    /**
     * 多目录全量索引；full=true 表示强制重建（CREATE，覆盖旧索引）。
     */
    public int indexFolders(List<Path> roots, boolean full) throws IOException {
        CONSECUTIVE_EXTRACT_TIMEOUTS.set(0);
        extractionBreakerOpen = false;
        if (full) {
            // No intermediate commit: the rebuilt index swaps in atomically at
            // the final commit below. Committing deleteAll() up front left an
            // empty index visible for the whole rebuild — and permanently on
            // disk if the app exited mid-run (searches then returned 0 hits
            // with no error).
            writer.deleteAll();
        }

        AtomicInteger count = new AtomicInteger(0);
        if (roots != null) {
            for (Path root : roots) {
                if (root == null) continue;
                walkOneRoot(this.writer, root, count);
            }
        }
        writer.commit();
        return count.get();
    }

    /** Hard recursion bound — a pathological nesting depth cannot blow the stack. */
    private static final int MAX_WALK_DEPTH = 512;

    private void walkOneRoot(IndexWriter writer, Path root, AtomicInteger count) {
        try {
            if (!Files.exists(root)) {
                log.warn("Root path does not exist: {}", root);
                return;
            }
            // Recursive walk rather than Files.walkFileTree: a directory whose
            // listing fails mid-iteration (cloud file-provider placeholders
            // like OneDrive .Trash, permission errors, vanished dirs) must only
            // lose its own subtree, not abort the whole root — walkFileTree
            // propagates such failures and ends the scan. Links are followed,
            // so a symlinked source root (~/OneDrive on macOS) is walked
            // instead of indexed as a single file record.
            walkDir(writer, root, count, new HashSet<Object>());
        } catch (Throwable t) {
            log.error("Walk tree error for root {}: {}", root, t.getMessage());
        }
    }

    private void walkDir(IndexWriter writer, Path path, AtomicInteger count, Set<Object> ancestry) {
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
                return;
            }
            if (!attrs.isDirectory()) {
                try {
                    indexFile(writer, path, attrs);
                    count.incrementAndGet();
                } catch (Throwable t) {
                    log.warn("Visit file error: {}, exception: {}", path, t.getMessage());
                }
                return;
            }
            try {
                indexDirectory(writer, path, attrs);
                count.incrementAndGet();
            } catch (Throwable t) {
                log.warn("Pre-visit directory error: {}, exception: {}", path, t.getMessage());
            }
            if (ancestry.size() >= MAX_WALK_DEPTH) {
                log.warn("Max directory depth reached, skipping subtree: {}", path);
                return;
            }
            // Link-cycle detection across the current ancestor chain; fileKey
            // (dev+inode) is free and needs no extra real-path resolution.
            Object key = attrs.fileKey();
            if (key != null && !ancestry.add(key)) {
                return;
            }
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
                for (Path child : stream) {
                    walkDir(writer, child, count, ancestry);
                }
            } catch (IOException | DirectoryIteratorException e) {
                // Scoped to this directory only — siblings keep walking.
                log.warn("Directory listing failed: {}, exception: {}", path, e.getMessage());
            } finally {
                if (key != null) {
                    ancestry.remove(key);
                }
            }
        } catch (Throwable t) {
            log.warn("Walk directory error: {}, exception: {}", path, t.getMessage());
        }
    }

    /* --------------------------- 核心索引逻辑 --------------------------- */

    private void indexDirectory(IndexWriter writer, Path dir, BasicFileAttributes attrs) {
        try {
            String pathStr = Utils.normalizeForIndex(dir);
            writer.updateDocument(new Term("path", pathStr), buildDirDoc(dir, attrs));
            Tombstones.remove(pathStr);
            recordUpsert(pathStr);
        } catch (Throwable t) {
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
                    && !extractionBreakerOpen
                    && !shouldSkipContentExtraction(file, attrs)) {
                content = extractTextReadOnly(file, attrs, settings);
            }
            mime = Files.probeContentType(file);
        } catch (Throwable e) {
            log.warn("Get mime/content error: {}, exception: {}", file, e.getMessage());
        }

        if (mime != null) doc.add(new StringField("mime", mime, Field.Store.YES));
        if (!content.isEmpty()) {
            doc.add(new TextField("content", content, Field.Store.NO));
            doc.add(new TextField("content_zh", content, Field.Store.NO));
            doc.add(new TextField("content_ja", content, Field.Store.NO));
        }
        return doc;
    }

    private void indexFile(IndexWriter writer, Path file, BasicFileAttributes attrs) {
        try {
            Document doc = buildFileDoc(file, attrs, settings);
            if (doc == null) return;
            String pathStr = Utils.normalizeForIndex(file);
            writer.updateDocument(new Term("path", pathStr), doc);
            Tombstones.remove(pathStr);
            recordUpsert(pathStr);
        } catch (Throwable t) {
            log.warn("Index file error: {}, exception: {}", file, t.getMessage());
        }
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

    private static String extractTextReadOnly(Path file, BasicFileAttributes attrs, IndexSettings settings) {
        Future<String> fut;
        try {
            fut = PARSER_POOL.submit(() -> {
                try (InputStream is = Files.newInputStream(file, StandardOpenOption.READ)) {
                    Metadata md = new Metadata();
                    md.set(org.apache.tika.metadata.TikaCoreProperties.RESOURCE_NAME_KEY, file.getFileName().toString());
                    int maxChars = settings.maxExtractChars > 0 ? settings.maxExtractChars : DEFAULT_MAX_CHARS;
                    BodyContentHandler handler = new BodyContentHandler(maxChars);
                    ParseContext ctx = new ParseContext();
                    try {
                        tikaParser.parse(is, handler, md, ctx);
                        clearFailedExtraction(file);
                        return handler.toString();
                    } catch (Throwable e) {
                        String msg = e.getMessage() == null ? "" : e.getMessage();
                        if (isWriteLimitReached(msg)) {
                            clearFailedExtraction(file);
                            log.info("Tika extract truncated by index.maxExtractChars={} for {}", maxChars, file);
                            return handler.toString();
                        }
                        if (isSevereExtractionError(msg)) {
                            rememberFailedExtraction(file, attrs);
                        }
                        log.warn("Tika parse failed for {}: {}", file, msg);
                        return "";
                    }
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException rex) {
            // Pool saturated — index without content rather than queueing
            // unboundedly. NOT recorded in FAILED_CONTENT_CACHE: saturation is
            // transient, so the file must be retried on the next index pass
            // instead of being skipped forever on unchanged size/mtime.
            log.warn("Tika parser pool saturated; content skipped for {}", file);
            return "";
        }
        try {
            String extracted = fut.get(settings.parseTimeoutSec, TimeUnit.SECONDS);
            // The provider responded (empty string from a fast parse failure
            // also lands here): the stall streak is broken.
            CONSECUTIVE_EXTRACT_TIMEOUTS.set(0);
            return extracted;
        } catch (java.util.concurrent.TimeoutException te) {
            // Cancel first: an unstarted task is removed from the queue; a
            // running one gets interrupted. A native read parked on a stalled
            // cloud file-provider cannot be interrupted — the tika thread stays
            // pinned until the OS eventually answers, bounded only by the pool cap.
            fut.cancel(true);
            PARSER_POOL.purge();
            int streak = CONSECUTIVE_EXTRACT_TIMEOUTS.incrementAndGet();
            if (!extractionBreakerOpen && streak >= EXTRACT_TIMEOUT_BREAKER_LIMIT) {
                extractionBreakerOpen = true;
                log.warn("Extraction timed out {} times in a row — degrading to "
                        + "name-only indexing for the rest of this run. Likely a "
                        + "stalled cloud file-provider (on-demand placeholder reads "
                        + "park in the kernel and cannot be interrupted). File names "
                        + "keep indexing; content coverage for these files resumes on "
                        + "a later index pass or after pinning the folder locally.", streak);
            }
            log.warn("Extract text timeout or error: {}, exception: {}", file, te.getMessage());
            // A timeout is usually a slow share, not a corrupt file — skip
            // re-parsing for a bounded window via the failed cache so a hang
            // does not repeat every scan, but keep the retry path alive.
            rememberFailedExtraction(file, attrs);
            return "";
        } catch (Throwable t) {
            CONSECUTIVE_EXTRACT_TIMEOUTS.set(0);
            fut.cancel(true);
            PARSER_POOL.purge();
            log.warn("Extract text timeout or error: {}, exception: {}", file, t.getMessage());
            rememberFailedExtraction(file, attrs);
            return "";
        }
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

    private static boolean isWriteLimitReached(String msg) {
        String lower = msg.toLowerCase(Locale.ROOT);
        return lower.contains("limit has been reached") || lower.contains("more than") && lower.contains("characters");
    }

    private static boolean isSevereExtractionError(String msg) {
        if (msg == null || msg.isEmpty()) {
            return true;
        }
        return !isWriteLimitReached(msg);
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
        if (isTextMime(file)) return true;
        return looksLikeText(file);
    }

    private static boolean isTextMime(Path file) {
        try {
            String mime = Files.probeContentType(file);
            if (mime == null) return false;
            mime = mime.toLowerCase(Locale.ROOT);
            return mime.startsWith("text/")
                    || mime.equals("application/json")
                    || mime.equals("application/xml")
                    || mime.equals("application/x-yaml")
                    || mime.equals("application/yaml")
                    || mime.equals("application/javascript")
                    || mime.equals("application/x-javascript")
                    || mime.equals("application/x-sh")
                    || mime.equals("application/x-java-source");
        } catch (Throwable e) {
            log.warn("MIME probe failed for {}: {}", file, e.getMessage());
            return false;
        }
    }

    private static boolean looksLikeText(Path file) {
        byte[] buf = new byte[4096];
        try (InputStream is = Files.newInputStream(file, StandardOpenOption.READ)) {
            int n = is.read(buf);
            if (n <= 0) return false;
            int printable = 0;
            for (int i = 0; i < n; i++) {
                int b = buf[i] & 0xFF;
                if (b == 0x00) return false;
                if (b == 0x09 || b == 0x0A || b == 0x0D) printable++;
                else if (b >= 0x20 && b <= 0x7E) printable++;
            }
            double ratio = printable / (double) n;
            return ratio >= 0.85;
        } catch (Throwable e) {
            log.warn("Text sniffing failed for {}: {}", file, e.getMessage());
            return false;
        }
    }
}
