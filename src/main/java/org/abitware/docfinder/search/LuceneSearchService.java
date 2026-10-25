package org.abitware.docfinder.search;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.analysis.ja.JapaneseAnalyzer;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.ConstantScoreQuery;
import org.apache.lucene.search.DisjunctionMaxQuery;
import org.apache.lucene.search.Explanation;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.SearcherFactory;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.WildcardQuery;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.NRTCachingDirectory;

import org.abitware.docfinder.index.IndexSettings;
import org.abitware.docfinder.index.IndexWriteCoordinator;
import org.abitware.docfinder.index.LuceneIndexer;
import org.abitware.docfinder.index.SourceManager;
import org.abitware.docfinder.util.PathScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 仅按 name 字段搜索（下一步再扩展 content） */
public class LuceneSearchService implements SearchService {
    private static final Logger log = LoggerFactory.getLogger(LuceneSearchService.class);
    private static final String FIELD_NAME = "name";
    private static final String FIELD_NAME_RAW = "name_raw";
    private static final String FIELD_PATH = "path";
    private static final String FIELD_PATH_SCOPE = "path_scope";
    private static final String FIELD_EXT = "ext";
    private static final String FIELD_KIND = "kind";
    private static final String FIELD_MTIME_DISPLAY = "mtime";
    private static final String FIELD_ATIME_DISPLAY = "atime";
    private static final String FIELD_CTIME_DISPLAY = "ctime";
    private static final String FIELD_SIZE = "size";

    private static final String KIND_FOLDER = "folder";

    private static final String[] CONTENT_FIELDS = { "content", "content_zh", "content_ja" };

    private final Path indexDir;
    private final Analyzer queryAnalyzer;
    private final SourceRootProvider sourceRootProvider;

    /**
     * Per-reader verdicts for the path_scope coverage gate, keyed by the top
     * reader's ReaderCacheHelper key (deletions change the key, core key would
     * not). Only SUPPORTED/REBUILD_REQUIRED are cached — never input errors,
     * source validation, or I/O failures. Entries are removed by the reader's
     * close listener and all cleared on service close.
     */
    private final Map<Object, Boolean> scopeSupportCache =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Set<Object> scopeListenerKeys =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    /**
     * close() clears the cache and flips this flag under scopePublishLock;
     * a check that started before close() must not publish its verdict
     * afterwards (QA24-07).
     */
    private volatile boolean closed = false;
    private final Object scopePublishLock = new Object();
    private final java.util.concurrent.atomic.AtomicLong scopeVerdictComputations =
            new java.util.concurrent.atomic.AtomicLong();

    // SearcherManager based on Directory (read-only) to avoid write.lock ownership in search service
    private volatile SearcherManager searcherManager;
    private volatile Directory directory;
    private final IndexSettings indexSettings;

    /**
     * Ghost-filter bounds. Checks run sequentially per search on a bounded
     * pool: at most one in-flight stat per search and a small queue overall.
     * PER_CHECK_TIMEOUT_MS bounds each individual wait only — it is NOT a
     * completion budget: refill continues until the page is full or the
     * candidate tail is reached, and an uncertain path is kept and reported
     * rather than treated as proof either way.
     */
    private static final int PER_CHECK_TIMEOUT_MS = 250;
    /** Candidates fetched per searchAfter page — scheduling granule only. */
    private static final int REFILL_PAGE = 64;
    /** Ghost paths held for cleanup per search and globally, bounded. */
    private static final int GHOST_BUFFER_CAP = 4096;
    private static final int GHOST_PURGE_BATCH = 512;

    /** Short-TTL verdicts for confirmed ghosts so repeat searches don't re-stat. */
    private static final long VERDICT_TTL_MS = 20_000L;
    private static final int VERDICT_CACHE_MAX = 4096;
    /** After a source root times out or is unreachable, its paths are kept
     * unchecked for this window instead of re-probing a dead share. */
    private static final long ROOT_BACKOFF_MS = 30_000L;

    /** Per-search refill state. Holds only bounded data — no full candidate history. */
    private static final class RefillState {
        final Set<String> ghosts = new HashSet<>();
        long generation = -1L;
        /** Session counter this request was issued under (see sessionGens). */
        java.util.concurrent.atomic.AtomicLong sessionGen;
        boolean hadUncertain;
        /** Lazily-loaded source roots for source-bounded reachability. */
        List<Path> roots;
        boolean rootsLoaded;
        /**
         * Set when the provider FAILED to load roots — distinct from an
         * empty config: without a known root list no reachability bound can
         * be established, so every path must be preserved as UNCERTAIN.
         */
        boolean rootsFailed;

        void addGhost(String p) {
            if (ghosts.size() < GHOST_BUFFER_CAP) {
                ghosts.add(p);
            }
        }
    }

    /** Verdict of a filesystem existence check. UNCERTAIN keeps the document. */
    enum PathVerdict { ALIVE, GHOST, UNCERTAIN, ROOT_UNREACHABLE }

    /**
     * Cancellation is scoped per caller session (SearchRequest.clientTag):
     * a newer request supersedes only in-flight work of the SAME caller —
     * desktop keystrokes, two web clients and other requesters never cancel
     * each other. Bounded at 64 tags; a null-tag request gets a private
     * counter and is therefore never superseded.
     */
    private static final int MAX_SESSIONS = 64;
    private final Map<String, java.util.concurrent.atomic.AtomicLong> sessionGens =
            new java.util.LinkedHashMap<String, java.util.concurrent.atomic.AtomicLong>();

    private java.util.concurrent.atomic.AtomicLong sessionGenFor(String tag) {
        if (tag == null) {
            return new java.util.concurrent.atomic.AtomicLong();
        }
        synchronized (sessionGens) {
            java.util.concurrent.atomic.AtomicLong g = sessionGens.get(tag);
            if (g == null) {
                if (sessionGens.size() >= MAX_SESSIONS) {
                    java.util.Iterator<String> it = sessionGens.keySet().iterator();
                    it.next();
                    it.remove();
                }
                g = new java.util.concurrent.atomic.AtomicLong();
                sessionGens.put(tag, g);
            }
            return g;
        }
    }

    private static boolean isStale(RefillState st) {
        return st.sessionGen != null && st.sessionGen.get() != st.generation;
    }

    /**
     * Shared bounded pool for existence stats — one pool for the WHOLE JVM,
     * not per service instance: closing/reopening a service can never pile
     * up additional blocked-stat workers. 4 workers cover concurrent
     * searches; a bounded queue + AbortPolicy means overflow degrades to
     * UNCERTAIN keeps, and idle daemon threads expire after 60s.
     */
    static final java.util.concurrent.ThreadPoolExecutor EXISTENCE_POOL;
    static {
        java.util.concurrent.ThreadPoolExecutor p = new java.util.concurrent.ThreadPoolExecutor(
                4, 4, 60L, TimeUnit.SECONDS,
                new java.util.concurrent.ArrayBlockingQueue<Runnable>(128),
                new ThreadFactory() {
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "docfinder-existence");
                        t.setDaemon(true);
                        return t;
                    }
                });
        p.allowCoreThreadTimeOut(true);
        EXISTENCE_POOL = p;
    }

    private static final class CachedVerdict {
        final long expiryMillis;
        CachedVerdict(long expiryMillis) {
            this.expiryMillis = expiryMillis;
        }
    }

    /** Only confirmed ghosts are cached — aliveness is always re-checked. */
    private final Map<String, CachedVerdict> verdictCache =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** Normalized source-root scope → wall time when probing may resume. */
    private final Map<String, Long> rootRetryAt =
            new java.util.concurrent.ConcurrentHashMap<>();

    private final ExecutorService ghostCleaner = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "docfinder-ghost-purge");
            t.setDaemon(true);
            return t;
        }
    });

    /**
     * Bounded merge-set of paths awaiting purge plus a single-flight drain
     * flag: repeated searches coalesce into the same backlog, and at most
     * one drain task is ever queued/running — no unbounded task pile-up.
     */
    private final Set<String> ghostDrainSet =
            Collections.synchronizedSet(new java.util.LinkedHashSet<String>());
    private final java.util.concurrent.atomic.AtomicBoolean ghostDrainActive =
            new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * Resurrection: tombstoned paths (see {@link org.abitware.docfinder.index.Tombstones})
     * are re-checked on a single-flight background pass kicked at service
     * construction and paced from every search — a file that comes back is
     * re-indexed without any watcher/poller coverage.
     */
    private static final int RESURRECT_BATCH = 256;
    private static final int RESURRECT_TIMEOUT_GIVEUP = 8;
    private static final long RESURRECT_PACE_MS = 3_000;
    /**
     * Periodic resurrection sweep: tombstoned files that come back are
     * re-indexed even while the app is completely IDLE — restoration must
     * not depend on the user running another search.
     */
    private static final long RESURRECT_SWEEP_MS = 1_500;
    private final java.util.concurrent.atomic.AtomicBoolean resurrectActive =
            new java.util.concurrent.atomic.AtomicBoolean();
    private volatile long lastResurrectAt;
    /**
     * Rotating cursor over the tombstone list: each pass resumes AFTER the
     * last examined key (wrapping at the end), so a head full of
     * still-missing files can never starve the tail.
     */
    private volatile String lastResurrectKey;
    private final java.util.concurrent.ScheduledExecutorService resurrectTimer =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "docfinder-resurrect");
                t.setDaemon(true);
                return t;
            });

    /** Fired after any background index write (ghost purge, resurrection). */
    private volatile Runnable indexChangeListener;

    /**
     * Last source-roots snapshot loaded by request processing. Background
     * tasks (purge, resurrection) must never call the provider themselves —
     * that would break the at-most-once-per-request contract — so they only
     * ever read this cache; a null cache defers them to a later search.
     */
    private volatile List<Path> lastRootsSnapshot;
    /** nanoTime of the last SUCCESSFUL provider load; 0 when never loaded. */
    private volatile long rootsLoadedAt;
    /**
     * The last provider load failed: the sweep retries on every pass (the
     * snapshot must never freeze on a transient IOException), and request
     * paths stay conservative via RefillState.rootsFailed.
     */
    private volatile boolean rootsStale;
    /** Background snapshot freshness bound — source add/remove are picked
     *  up within this window instead of requiring a request. */
    private static final long ROOTS_SNAPSHOT_TTL_MS = 30_000;

    public void setIndexChangeListener(Runnable listener) {
        this.indexChangeListener = listener;
    }

    private void fireIndexChange() {
        Runnable l = indexChangeListener;
        if (l != null) {
            try {
                l.run();
            } catch (RuntimeException ignore) {
            }
        }
    }

    public LuceneSearchService(Path indexDir) throws IOException {
        this(indexDir, new IndexSettings());
    }

    public LuceneSearchService(Path indexDir, IndexSettings settings) throws IOException {
        this(indexDir, settings, defaultProvider());
    }

    public LuceneSearchService(Path indexDir, IndexSettings settings,
                               SourceRootProvider sourceRootProvider) throws IOException {
        this.indexDir = indexDir;
        this.indexSettings = (settings == null) ? new IndexSettings() : settings;
        this.sourceRootProvider =
                (sourceRootProvider == null) ? defaultProvider() : sourceRootProvider;

        Analyzer std = new StandardAnalyzer();
        Analyzer zh  = new SmartChineseAnalyzer();
        Analyzer ja  = new JapaneseAnalyzer();
        Map<String, Analyzer> perField = new HashMap<>();
        perField.put(FIELD_NAME, std);
        perField.put("content", std);
        perField.put("content_zh", zh);
        perField.put("content_ja", ja);

        this.queryAnalyzer = new PerFieldAnalyzerWrapper(std, perField);

        // When nrtCacheMaxMB > 0, wrap FSDirectory with NRTCachingDirectory to keep recently-written
        // small segment files in memory, reducing disk I/O for NRT reads after incremental commits.
        Directory fsDir = FSDirectory.open(indexDir);
        if (settings != null && settings.nrtCacheMaxMB > 0) {
            // NRTCachingDirectory(delegate, maxMergeSizeMB, maxCachedMB)
            this.directory = new NRTCachingDirectory(fsDir, 5.0, settings.nrtCacheMaxMB);
        } else {
            this.directory = fsDir;
        }
        try {
            this.searcherManager = new SearcherManager(directory, new SearcherFactory());
        } catch (IOException | RuntimeException e) {
            // Constructor failure (e.g. IndexNotFoundException before the first
            // commit) must not leak the directory — the caller can only retry.
            this.directory.close();
            throw e;
        }
        // Seed the roots cache once at open: background work (resurrection
        // sweeps, ghost purges) must be able to scope paths to configured
        // sources without a request loading them first — a global search
        // with zero candidates never loads roots, which would otherwise
        // leave tombstones unexaminable forever.
        lastRootsSnapshot = safeLoadRoots();
        if (lastRootsSnapshot == null) {
            rootsStale = true; // retry on the first sweep pass
        } else {
            rootsLoadedAt = System.nanoTime();
        }
        // Periodic sweep + one immediate pass: tombstoned files that came
        // back while the app was down — or while it idles — are re-indexed
        // without waiting for the next search.
        resurrectTimer.scheduleWithFixedDelay(this::resurrectSweepSafe,
                RESURRECT_SWEEP_MS, RESURRECT_SWEEP_MS, TimeUnit.MILLISECONDS);
        kickResurrection();
    }

    /** Provider load that tolerates failure — null means "retry later". */
    private List<Path> safeLoadRoots() {
        try {
            return sourceRootProvider.loadRoots();
        } catch (Exception e) {
            return null;
        }
    }

    /** Timer entry: a bounded pass per tick, same single-flight discipline. */
    private void resurrectSweepSafe() {
        if (closed || org.abitware.docfinder.index.Tombstones.isEmpty()) {
            return;
        }
        if (!resurrectActive.compareAndSet(false, true)) {
            return;
        }
        try {
            ghostCleaner.submit(this::drainResurrections);
        } catch (RuntimeException rejected) {
            resurrectActive.set(false);
        }
    }

    /**
     * Exact coverage gate over all live docs of the searcher's reader (D4).
     * Fast path: per leaf, numDocs()==0 skips; a non-null Terms whose
     * getDocCount()==maxDoc() proves every doc in that leaf carries the field
     * (deleted docs still counted in maxDoc → may force the fallback, never a
     * wrong verdict). Inconclusive leaves degrade to an exact query locating
     * one live doc without any path_scope term.
     */
    static void ensureDirectoryScopeSupported(IndexSearcher searcher) {
        IndexReader reader = searcher.getIndexReader();
        boolean allLeavesProven = true;
        boolean anyLiveLeaf = false;
        try {
            for (org.apache.lucene.index.LeafReaderContext ctx : reader.leaves()) {
                org.apache.lucene.index.LeafReader leaf = ctx.reader();
                if (leaf.numDocs() == 0) {
                    continue;
                }
                anyLiveLeaf = true;
                org.apache.lucene.index.Terms terms = leaf.terms(FIELD_PATH_SCOPE);
                if (terms == null || terms.getDocCount() != leaf.maxDoc()) {
                    allLeavesProven = false;
                    break;
                }
            }
        } catch (IOException e) {
            throw new SearchException(SearchException.Code.SCOPE_CHECK_FAILED,
                    "could not inspect index for path_scope coverage: " + e.getMessage(), e);
        }
        if (!anyLiveLeaf || allLeavesProven) {
            return;
        }
        // Exact fallback: does any LIVE doc lack a path_scope term?
        try {
            BooleanQuery.Builder missing = new BooleanQuery.Builder();
            missing.add(new MatchAllDocsQuery(), BooleanClause.Occur.MUST);
            missing.add(new PrefixQuery(new Term(FIELD_PATH_SCOPE, "")),
                    BooleanClause.Occur.MUST_NOT);
            TopDocs td = searcher.search(missing.build(), 1);
            if (td.totalHits.value > 0) {
                throw new SearchException(SearchException.Code.INDEX_REBUILD_REQUIRED,
                        "index contains documents without path_scope; run Index All Sources or Full Rebuild");
            }
        } catch (IOException e) {
            throw new SearchException(SearchException.Code.SCOPE_CHECK_FAILED,
                    "could not verify path_scope coverage: " + e.getMessage(), e);
        }
    }

    int scopeSupportCacheSize() {
        return scopeSupportCache.size();
    }

    /**
     * Number of times a scope verdict was actually computed by calling
     * ensureDirectoryScopeSupported — distinct from scopeSupportCacheSize()
     * (entries may be overwritten/evicted; this counter only increments).
     * Test/evidence seam for R24-05.
     */
    long scopeVerdictComputationCount() {
        return scopeVerdictComputations.get();
    }

    /**
     * Cached gate for a live acquired searcher. Caches by reader cache-helper
     * key; when helper is null the check runs uncached.
     */
    void checkScopeSupport(IndexSearcher searcher) {
        IndexReader reader = searcher.getIndexReader();
        IndexReader.CacheHelper helper = reader.getReaderCacheHelper();
        Object key = (helper == null) ? null : helper.getKey();
        if (key != null) {
            Boolean cached = scopeSupportCache.get(key);
            if (cached != null) {
                if (!cached) {
                    throw new SearchException(SearchException.Code.INDEX_REBUILD_REQUIRED,
                            "index contains documents without path_scope; run Index All Sources or Full Rebuild");
                }
                return;
            }
        }
        try {
            scopeVerdictComputations.incrementAndGet();
            ensureDirectoryScopeSupported(searcher);
        } catch (SearchException e) {
            // Only the deterministic coverage verdict is cacheable; transient
            // I/O/check failures must be re-evaluated on the next request.
            if (key != null && e.getCode() == SearchException.Code.INDEX_REBUILD_REQUIRED) {
                publishScopeVerdict(reader, key, Boolean.FALSE);
            }
            throw e;
        }
        if (key != null) {
            publishScopeVerdict(reader, key, Boolean.TRUE);
        }
    }

    /**
     * Test seam (QA24-07): invoked after a scope verdict is computed, before
     * it is published into the cache.
     */
    void onScopeVerdictComputed() {
    }

    private void publishScopeVerdict(IndexReader reader, Object key, boolean verdict) {
        onScopeVerdictComputed();
        synchronized (scopePublishLock) {
            if (closed || key == null) {
                return;
            }
            if (registerCloseListener(reader, key)) {
                scopeSupportCache.put(key, verdict);
            }
        }
    }

    /**
     * Registers a closed listener on the reader's CacheHelper that evicts the
     * cache entry for this reader. Returns false when helper is null or
     * registration fails → caller must not cache.
     */
    private boolean registerCloseListener(IndexReader reader, Object key) {
        IndexReader.CacheHelper helper = reader.getReaderCacheHelper();
        if (helper == null) {
            return false;
        }
        if (!scopeListenerKeys.add(key)) {
            return true; // already registered for this key
        }
        try {
            helper.addClosedListener(cacheKey -> {
                scopeSupportCache.remove(cacheKey);
                scopeListenerKeys.remove(cacheKey);
            });
            return true;
        } catch (RuntimeException e) {
            scopeListenerKeys.remove(key);
            log.warn("Could not register reader close listener; scope verdict will not be cached: {}",
                    e.getMessage());
            return false;
        }
    }

    private static SourceRootProvider defaultProvider() {
        SourceManager sm = new SourceManager();
        return sm::loadSearchRoots;
    }

    /**
     * Validates the requested directory against one snapshot of configured
     * sources. Pure comparison: no filesystem or network access. Order per
     * D4: normalize → load snapshot → coverage check. The loaded snapshot is
     * also seeded into `st` so the ghost filter's source-bounded reachability
     * reuses it — still one provider call per request.
     */
    private String resolveScopedDirectory(String directoryPath, RefillState st) throws IOException {
        String normalized;
        try {
            normalized = PathScope.normalizeDirectory(directoryPath);
        } catch (IllegalArgumentException e) {
            throw new SearchException(SearchException.Code.INVALID_DIRECTORY,
                    "directory is not a legal absolute path: " + e.getMessage(), e);
        }
        if (normalized == null) {
            return null;
        }
        List<Path> roots;
        try {
            roots = sourceRootProvider.loadRoots();
        } catch (IOException e) {
            throw new SearchException(SearchException.Code.SOURCE_CONFIG_UNAVAILABLE,
                    "search sources could not be loaded: " + e.getMessage(), e);
        }
        lastRootsSnapshot = roots;
        rootsLoadedAt = System.nanoTime();
        rootsStale = false;
        if (st != null) {
            st.roots = roots;
            st.rootsLoaded = true;
        }
        for (Path root : roots) {
            if (root == null) continue;
            String r;
            try {
                r = PathScope.normalizeIndexedPath(root);
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (normalized.equals(r)
                    || normalized.startsWith(PathScope.descendantPrefix(r))) {
                return normalized;
            }
        }
        throw new SearchException(SearchException.Code.DIRECTORY_NOT_INDEXED,
                "directory is outside configured sources: " + normalized);
    }

    /**
     * Directory restriction as a FILTER clause: the selected directory itself
     * counts only when it is a folder document; every descendant of the whole
     * tree matches via the trailing-slash prefix (D4).
     */
    private void addDirectoryFilter(BooleanQuery.Builder root, String normalizedDirectory) {
        BooleanQuery.Builder self = new BooleanQuery.Builder();
        self.add(new TermQuery(new Term(FIELD_PATH_SCOPE, normalizedDirectory)),
                BooleanClause.Occur.MUST);
        self.add(new TermQuery(new Term(FIELD_KIND, KIND_FOLDER)),
                BooleanClause.Occur.MUST);

        BooleanQuery.Builder dir = new BooleanQuery.Builder();
        dir.add(self.build(), BooleanClause.Occur.SHOULD);
        dir.add(new PrefixQuery(new Term(FIELD_PATH_SCOPE,
                PathScope.descendantPrefix(normalizedDirectory))),
                BooleanClause.Occur.SHOULD);
        root.add(dir.build(), BooleanClause.Occur.FILTER);
    }

    @Override
    public List<SearchResult> search(SearchRequest request) {
        // One-direction delegation: searchDetailed is the execution core (R1-04).
        return searchDetailed(request).results;
    }

    @Override
    public SearchOutcome searchDetailed(SearchRequest request) {
        List<SearchResult> out = new ArrayList<>();
        // Session-scoped supersede: bump THIS caller's counter so a newer
        // query from the same client (including a blank/cleared one, which
        // is exactly what "input changed" signals) cancels its own older
        // in-flight work — without ever touching other callers' searches.
        java.util.concurrent.atomic.AtomicLong sessionGen =
                sessionGenFor(request == null ? null : request.clientTag);
        long generation = sessionGen.incrementAndGet();
        kickResurrection(); // paced no-op unless tombstoned paths exist
        if (request == null) return new SearchOutcome(out, TriState.FALSE);

        String rawQuery = request.query == null ? "" : request.query.trim();
        // Blank query short-circuits before directory/source/syntax checks (D3).
        if (rawQuery.isEmpty()) return new SearchOutcome(out, TriState.FALSE);

        FilterState filter = request.filter;
        SearchScope scope = request.scope == null ? SearchScope.ALL : request.scope;
        MatchMode matchMode = request.matchMode == null ? MatchMode.FUZZY : request.matchMode;

        RefillState st = new RefillState();
        st.sessionGen = sessionGen;
        st.generation = generation;
        String scopedDir;
        try {
            scopedDir = resolveScopedDirectory(request.directoryPath, st);
        } catch (SearchException e) {
            throw e;
        } catch (IOException e) {
            throw new SearchException(SearchException.Code.SOURCE_CONFIG_UNAVAILABLE,
                    "search sources could not be loaded: " + e.getMessage(), e);
        }

        IndexSearcher searcher = null; // Declare outside try for finally block
        try {
            searcherManager.maybeRefresh(); // Refresh searcher if index has changed
            searcher = searcherManager.acquire(); // Acquire a searcher

            if (scopedDir != null) {
                checkScopeSupport(searcher);
            }

            NamePreprocess np = extractNameWildcards(rawQuery);
            // Frozen steering flag: the original advanced-syntax set only.
            // The new literal classification adds its own exclusions inside
            // literalTokensIfEligible and must not alter EXACT/advanced
            // routing (regression: 'foo AND bar' and 'report (' changed).
            boolean advanced = hasAdvancedSyntax(np.qRest);
            List<String> literalTokens = literalTokensIfEligible(np.qRest, scope, matchMode, advanced);

            int limit = Math.max(1, Math.min(request.limit, 200));
            int fetchLimit = limit + 1; // limit+1 probing (D5)

            TriState hasMore;
            if (literalTokens != null && scope == SearchScope.ALL) {
                // D4 dual-list merge (R1-01: one acquired searcher for both
                // branches, doc fetches and explanations; second-branch
                // failure propagates as SEARCH_FAILED, no partial success).
                hasMore = searchAllLiteral(searcher, out, np, literalTokens,
                        scope, filter, scopedDir, limit, fetchLimit, st);
            } else {
                Query userQuery;
                if (literalTokens != null) {
                    // NAME/FOLDER literal: name condition is the whole keyword query.
                    userQuery = buildNameCondition(literalTokens);
                } else {
                    userQuery = buildUserQuery(np.qRest, scope, matchMode, advanced);
                }

                BooleanQuery.Builder root = new BooleanQuery.Builder();
                if (userQuery == null || (userQuery instanceof BooleanQuery && ((BooleanQuery) userQuery).clauses().isEmpty())) {
                    root.add(new MatchAllDocsQuery(), BooleanClause.Occur.MUST);
                } else {
                    root.add(userQuery, BooleanClause.Occur.MUST);
                }

                List<Query> nameClauses = new ArrayList<>();
                addNameWildcardMust(root, np.nameWildcards, nameClauses);
                addKindFilter(root, scope);
                addFilters(root, filter);
                if (scopedDir != null) {
                    addDirectoryFilter(root, scopedDir);
                }

                boolean tierSort = literalTokens != null;
                boolean exhausted = collectValid(searcher, out, root.build(), fetchLimit,
                        userQuery, nameClauses, null, null, tierSort, st);
                hasMore = resolveHasMore(out.size(), limit, exhausted, st);
                if (out.size() > limit) {
                    out.subList(limit, out.size()).clear();
                }
            }
            enqueueGhostPurge(st.ghosts);
            kickResurrection(); // roots just loaded — a pass can run now
            return new SearchOutcome(out, hasMore, st.hadUncertain);
        } catch (SearchException ex) {
            throw ex;
        } catch (ParseException ex) {
            // Identical for scoped and unscoped requests (D3).
            log.warn("Invalid search query '{}': {}", rawQuery, ex.getMessage());
            throw new SearchException(SearchException.Code.INVALID_QUERY,
                    "invalid query syntax: " + ex.getMessage(), ex);
        } catch (org.apache.lucene.index.IndexNotFoundException ex) {
            throw new SearchException(SearchException.Code.INDEX_UNAVAILABLE,
                    "search index is unavailable: " + ex.getMessage(), ex);
        } catch (IOException ex) {
            throw new SearchException(SearchException.Code.SEARCH_FAILED,
                    "search failed: " + ex.getMessage(), ex);
        } catch (RuntimeException ex) {
            throw new SearchException(SearchException.Code.SEARCH_FAILED,
                    "search failed: " + ex.getMessage(), ex);
        } finally {
            if (searcher != null) {
                try {
                    searcherManager.release(searcher);
                } catch (IOException e) {
                    log.warn("Failed to release searcher: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * ALL + literal FUZZY: name-hit list (constant tiers only, no BM25) merged
     * before the content-only list (parsed content MUST + name MUST_NOT), so a
     * high-tier name hit can never be displaced by unbounded content scores
     * and can never be cut by a shared Top-N (D4/N02-01).
     */
    private TriState searchAllLiteral(IndexSearcher searcher, List<SearchResult> out,
                                      NamePreprocess np, List<String> literalTokens,
                                      SearchScope scope, FilterState filter, String scopedDir,
                                      int limit, int fetchLimit,
                                      RefillState st) throws IOException, ParseException {
        Query nameCond = buildNameCondition(literalTokens);
        Query contentQ = buildLiteralContentQuery(np.qRest); // null when analysis yields no terms

        // List 1: name condition + shared filters.
        BooleanQuery.Builder nameRoot = new BooleanQuery.Builder();
        nameRoot.add(nameCond, BooleanClause.Occur.MUST);
        List<Query> nameClauses = new ArrayList<>();
        addNameWildcardMust(nameRoot, np.nameWildcards, nameClauses);
        addKindFilter(nameRoot, scope);
        addFilters(nameRoot, filter);
        if (scopedDir != null) {
            addDirectoryFilter(nameRoot, scopedDir);
        }
        boolean exhausted = collectValid(searcher, out, nameRoot.build(), fetchLimit,
                null, null, nameCond, contentQ, true, st);

        // List 2: content-only hits fill the remaining quota; MUST_NOT keeps
        // the lists disjoint so name+content docs live only in list 1.
        if (out.size() < fetchLimit && contentQ != null && !isStale(st)) {
            BooleanQuery.Builder contentRoot = new BooleanQuery.Builder();
            contentRoot.add(contentQ, BooleanClause.Occur.MUST);
            contentRoot.add(nameCond, BooleanClause.Occur.MUST_NOT);
            addNameWildcardMust(contentRoot, np.nameWildcards, new ArrayList<Query>());
            addKindFilter(contentRoot, scope);
            addFilters(contentRoot, filter);
            if (scopedDir != null) {
                addDirectoryFilter(contentRoot, scopedDir);
            }
            exhausted = collectValid(searcher, out, contentRoot.build(), fetchLimit,
                    null, null, nameCond, contentQ, false, st) && exhausted;
        }

        TriState hasMore = resolveHasMore(out.size(), limit, exhausted, st);
        if (out.size() > limit) {
            out.subList(limit, out.size()).clear();
        }
        return hasMore;
    }

    /**
     * Reads candidates for one query branch via `searchAfter` cursor on the
     * caller's snapshot, materializing verified results into `out` until it
     * reaches targetSize or the branch tail is observed (a page came back
     * short). The cursor always advances to the last RAW hit of each page —
     * filtered ghosts still move it forward, so progress is guaranteed.
     * Returns true only when the tail was observed; a stale (superseded)
     * search returns false and its caller reports UNKNOWN rather than a
     * fabricated verdict.
     */
    private boolean collectValid(IndexSearcher searcher, List<SearchResult> out, Query query,
                                 int targetSize, Query userQuery, List<Query> nameClauses,
                                 Query nameQ, Query contentQ, boolean tierSort,
                                 RefillState st) throws IOException {
        ScoreDoc after = null;
        while (out.size() < targetSize && !isStale(st)) {
            TopDocs top = searcher.searchAfter(after, query, REFILL_PAGE);
            if (top.scoreDocs.length == 0) {
                return true; // no hits left — the whole branch was examined
            }
            appendResults(searcher, out, top, userQuery, nameClauses, nameQ, contentQ,
                    tierSort, st);
            if (isStale(st)) {
                return false; // superseded mid-page — must NOT be read as
                              // "tail reached" or a cancelled caller would
                              // report a fabricated complete page
            }
            after = top.scoreDocs[top.scoreDocs.length - 1];
            if (top.scoreDocs.length < REFILL_PAGE) {
                return true; // short page — tail reached
            }
        }
        return false;
    }

    /**
     * hasMore verdict after ghost-filtered refill: more verified hits than the
     * page → TRUE; every branch fully examined → FALSE; anything else (the
     * search was superseded before a conclusion could be proven) → UNKNOWN.
     */
    private static TriState resolveHasMore(int resultCount, int limit,
                                           boolean allBranchesExhausted, RefillState st) {
        if (resultCount > limit) {
            return TriState.TRUE;
        }
        if (allBranchesExhausted) {
            return TriState.FALSE;
        }
        return TriState.UNKNOWN;
    }

    /**
     * Existence classification of an indexed path. GHOST requires the path to
     * be confirmed gone AND a surviving ancestor reachable — with the walk
     * bounded at the containing source root (stopScope): reaching a vanished
     * source root yields ROOT_UNREACHABLE, never GHOST, so an offline share
     * can never be misread as a mass deletion. Permission/IO-indeterminate
     * stats are UNCERTAIN. Runs on EXISTENCE_POOL — may block on slow shares.
     */
    static PathVerdict classifyPath(String pathStr, String stopScope) {
        try {
            Path p = Paths.get(pathStr);
            if (Files.exists(p)) {
                return PathVerdict.ALIVE;
            }
            if (!Files.notExists(p)) {
                return PathVerdict.UNCERTAIN;
            }
            if (stopScope != null && stopScope.equals(PathScope.normalizeIndexedPath(p))) {
                // The vanished path IS the source root itself: ancestors of
                // the root prove nothing (a parent dir existing cannot tell
                // an offline share from a deleted share) — never GHOST.
                return PathVerdict.ROOT_UNREACHABLE;
            }
            for (Path a = p.getParent(); a != null; a = a.getParent()) {
                if (stopScope != null && stopScope.equals(PathScope.normalizeIndexedPath(a))) {
                    // Reached the containing source root — the walk may not
                    // pass beyond it: an existing parent of a missing share
                    // must not confirm a deletion.
                    return Files.exists(a) ? PathVerdict.GHOST : PathVerdict.ROOT_UNREACHABLE;
                }
                if (Files.exists(a)) {
                    return PathVerdict.GHOST;
                }
                if (!Files.notExists(a)) {
                    return PathVerdict.UNCERTAIN;
                }
            }
            return PathVerdict.UNCERTAIN;
        } catch (RuntimeException e) {
            return PathVerdict.UNCERTAIN;
        }
    }

    /** Test/back-compat seam: confirmed-deleted verdict without a source bound. */
    static boolean isDefinitelyDeleted(String pathStr) {
        return classifyPath(pathStr, null) == PathVerdict.GHOST;
    }

    /** Confirmed-deleted verdict bounded at the given source root. */
    static boolean isDefinitelyDeleted(String pathStr, Path sourceRoot) {
        String stop = sourceRoot == null ? null : PathScope.normalizeIndexedPath(sourceRoot);
        return classifyPath(pathStr, stop) == PathVerdict.GHOST;
    }

    /**
     * Source-bounded existence check on the bounded pool, awaited at most
     * PER_CHECK_TIMEOUT_MS — sequential per search, so at most one stat is
     * in flight per query and the pool queue can never pile up. Confirmed
     * ghosts are cached briefly; timeouts, unreachable roots, permission or
     * I/O-indeterminate results are UNCERTAIN: the hit is kept, the source
     * backed off, and the outcome flagged unverified rather than guessed.
     */
    private PathVerdict verifyPath(String pathStr, RefillState st) {
        long now = System.currentTimeMillis();
        CachedVerdict v = verdictCache.get(pathStr);
        if (v != null && v.expiryMillis > now) {
            return PathVerdict.GHOST; // only confirmed ghosts are ever cached
        }
        List<Path> roots = rootsFor(st);
        if (st.rootsFailed) {
            // Source list unreadable → no reachability bound exists →
            // nothing may be classified GHOST; keep and flag unverified.
            st.hadUncertain = true;
            return PathVerdict.UNCERTAIN;
        }
        Path root = containingRoot(pathStr, roots);
        String rootScope = (root == null) ? null : PathScope.normalizeIndexedPath(root);
        Long retryAt = rootScope == null ? null : rootRetryAt.get(rootScope);
        if (retryAt != null && retryAt > now) {
            st.hadUncertain = true;
            return PathVerdict.UNCERTAIN; // source recently unreachable → unchecked keep
        }
        PathVerdict verdict;
        final String stop = rootScope;
        java.util.concurrent.Future<PathVerdict> f;
        try {
            f = EXISTENCE_POOL.submit(() -> classifyPath(pathStr, stop));
        } catch (RuntimeException rejected) {
            st.hadUncertain = true;
            return PathVerdict.UNCERTAIN; // bounded queue full → unchecked keep
        }
        try {
            verdict = f.get(PER_CHECK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException te) {
            f.cancel(true);
            verdict = PathVerdict.UNCERTAIN;
            if (rootScope != null) {
                rootRetryAt.put(rootScope, now + ROOT_BACKOFF_MS);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            verdict = PathVerdict.UNCERTAIN;
        } catch (java.util.concurrent.ExecutionException | RuntimeException e) {
            verdict = PathVerdict.UNCERTAIN;
        }
        switch (verdict) {
            case GHOST:
                cacheVerdict(pathStr, now);
                return PathVerdict.GHOST;
            case ALIVE:
                // Never cache ALIVE — a file deleted between searches would be
                // masked by a stale verdict. Liveness is re-stat'ed each search.
                return PathVerdict.ALIVE;
            case ROOT_UNREACHABLE:
                if (rootScope != null) {
                    rootRetryAt.put(rootScope, now + ROOT_BACKOFF_MS);
                }
                st.hadUncertain = true;
                return PathVerdict.UNCERTAIN;
            default:
                st.hadUncertain = true;
                return PathVerdict.UNCERTAIN;
        }
    }

    private void cacheVerdict(String pathStr, long now) {
        if (verdictCache.size() >= VERDICT_CACHE_MAX) {
            verdictCache.clear();
        }
        verdictCache.put(pathStr, new CachedVerdict(now + VERDICT_TTL_MS));
    }

    private List<Path> rootsFor(RefillState st) {
        if (!st.rootsLoaded) {
            st.rootsLoaded = true;
            try {
                st.roots = sourceRootProvider.loadRoots();
                lastRootsSnapshot = st.roots;
                rootsLoadedAt = System.nanoTime();
                rootsStale = false;
            } catch (Exception e) {
                st.roots = Collections.emptyList();
                st.rootsFailed = true; // unreadable config ≠ empty config —
                                       // no bound → preserve everything
                rootsStale = true;     // sweep retries instead of freezing
                // Never publish the failure-empty list as the snapshot:
                // the last-good roots (or null) must survive a transient
                // provider error.
            }
        }
        return st.roots == null ? Collections.<Path>emptyList() : st.roots;
    }

    /**
     * Longest configured source root containing pathStr (normalized,
     * case-folded via PathScope). Null when the path is under no source.
     */
    private static Path containingRoot(String pathStr, List<Path> roots) {
        if (roots == null) {
            return null;
        }
        try {
            String pScope = PathScope.normalizeIndexedPath(Paths.get(pathStr));
            Path best = null;
            int bestLen = -1;
            for (Path r : roots) {
                if (r == null) {
                    continue;
                }
                String rScope = PathScope.normalizeIndexedPath(r);
                if (rScope == null) {
                    continue;
                }
                if ((pScope.equals(rScope)
                        || pScope.startsWith(PathScope.descendantPrefix(rScope)))
                        && rScope.length() > bestLen) {
                    best = r;
                    bestLen = rScope.length();
                }
            }
            return best;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Merges confirmed ghosts into a bounded backlog and schedules one
     * single-flight drain. The set is deduplicated and capacity-capped, the
     * drain task exists at most once, and each batch writes through the
     * shared coordinator — no unbounded queue of purge tasks.
     */
    private void enqueueGhostPurge(Set<String> ghosts) {
        if (ghosts == null || ghosts.isEmpty() || closed) {
            return;
        }
        synchronized (ghostDrainSet) {
            for (String g : ghosts) {
                if (ghostDrainSet.size() >= GHOST_BUFFER_CAP) {
                    break; // backlog full — leftovers are re-detected next search
                }
                ghostDrainSet.add(g);
            }
        }
        scheduleGhostDrain();
    }

    private void scheduleGhostDrain() {
        if (closed || !ghostDrainActive.compareAndSet(false, true)) {
            return;
        }
        try {
            ghostCleaner.submit(this::drainGhostQueue);
        } catch (RuntimeException rejected) {
            ghostDrainActive.set(false);
        }
    }

    private void drainGhostQueue() {
        try {
            while (true) {
                List<String> batch = new ArrayList<>(GHOST_PURGE_BATCH);
                synchronized (ghostDrainSet) {
                    java.util.Iterator<String> it = ghostDrainSet.iterator();
                    while (it.hasNext() && batch.size() < GHOST_PURGE_BATCH) {
                        batch.add(it.next());
                        it.remove();
                    }
                }
                if (batch.isEmpty()) {
                    return;
                }
                purgeGhostBatch(batch);
            }
        } finally {
            ghostDrainActive.set(false);
            synchronized (ghostDrainSet) {
                if (!ghostDrainSet.isEmpty()) {
                    scheduleGhostDrain();
                }
            }
        }
    }

    /**
     * One batch write under the shared coordinator. The closed flag and each
     * file's aliveness are re-checked under the lock, so a service that was
     * closed (or a file that came back) is never written against a stale
     * verdict — expired cleanup cannot delete newer index state.
     */
    /** A lock wait longer than this re-verifies each path before deleting —
     *  a file restored during a long wait must not be purged on a stale
     *  verdict. Recheck is capped per batch so a contended share can never
     *  park the write lock on thousands of slow stats. */
    private static final long PURGE_RECHECK_AFTER_MS = 300;
    private static final int PURGE_RECHECK_CAP = 64;
    /** Extra bounded stats for candidates whose upsert-generation evidence
     *  was evicted after classification. Over-budget candidates are skipped
     *  this pass — never deleted on an unverifiable verdict. */
    private static final int PURGE_EVIDENCE_RECHECK_CAP = 64;

    private void purgeGhostBatch(List<String> batch) {
        List<Path> roots = lastRootsSnapshot;
        if (roots == null || closed) {
            return; // no request has loaded roots yet — try a later pass
        }
        // Phase 1 — existence classification WITHOUT the global write lock:
        // slow stats (network shares) must never block an unrelated writer.
        List<String> confirmed = new ArrayList<>(batch.size());
        Map<String, Long> classifiedAt = new HashMap<>(batch.size() * 2);
        for (String s : batch) {
            if (closed) {
                return;
            }
            verdictCache.remove(s);
            // Capture the generation baseline BEFORE the verdict I/O: any
            // upsert that lands during classification — not only after it —
            // is newer than the verdict and must survive the queued delete.
            long checkStart = System.nanoTime();
            if (isDefinitelyDeleted(s, containingRoot(s, roots))) {
                confirmed.add(s);
                classifiedAt.put(s, checkStart);
            }
        }
        if (confirmed.isEmpty() || closed) {
            return;
        }
        long queuedAt = System.nanoTime();
        final boolean[] wroteAny = { false };
        IndexWriteCoordinator.run(() -> {
            if (closed) {
                return;
            }
            // Phase 2 — the write itself. Re-check `closed` under the lock,
            // and if the wait for the lock was long, re-stat each path once
            // (generation-style protection for files restored meanwhile).
            boolean recheck = TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - queuedAt) > PURGE_RECHECK_AFTER_MS;
            int rechecked = 0;
            int evidenceRechecked = 0;
            try (LuceneIndexer idx = new LuceneIndexer(indexDir, indexSettings)) {
                boolean deletedAny = false;
                for (String s : confirmed) {
                    if (closed) {
                        return;
                    }
                    // Generation check (no I/O): the path was re-indexed
                    // since classification BEGAN — the verdict is stale and
                    // the newer record must survive, whether the upsert
                    // landed mid-classification or during the lock wait.
                    Long ct = classifiedAt.get(s);
                    long upsertedAt = LuceneIndexer.upsertedAt(s);
                    if (ct != null && upsertedAt > ct) {
                        continue;
                    }
                    // "Never registered" (watermark at/below baseline) may
                    // still delete after the ordinary recheck. "Protection
                    // evicted" (watermark above baseline) cannot — that
                    // path gets its own bounded re-stat: file present →
                    // keep the record, budget spent → defer to a later
                    // pass whose baseline postdates the evictions.
                    if (ct != null && upsertedAt < 0
                            && LuceneIndexer.maxEvictedUpsertNs() > ct) {
                        if (evidenceRechecked >= PURGE_EVIDENCE_RECHECK_CAP) {
                            continue;
                        }
                        evidenceRechecked++;
                        if (!isDefinitelyDeleted(s, containingRoot(s, roots))) {
                            continue;
                        }
                    } else if (recheck && rechecked < PURGE_RECHECK_CAP) {
                        rechecked++;
                        if (!isDefinitelyDeleted(s, containingRoot(s, roots))) {
                            continue;
                        }
                    }
                    try {
                        idx.deletePath(Paths.get(s));
                        deletedAny = true;
                    } catch (RuntimeException ignore) {
                    }
                }
                if (!deletedAny) {
                    return; // nothing removed — skip commit/notify so a
                            // stubborn ghost cannot drive a purge→refresh→
                            // research→purge self-excited loop
                }
                idx.commit();
                wroteAny[0] = true;
            } catch (IOException | RuntimeException e) {
                log.debug("ghost purge write failed: {}", e.toString());
            }
        });
        if (!wroteAny[0]) {
            return;
        }
        try {
            searcherManager.maybeRefresh();
        } catch (IOException | RuntimeException e) {
            log.debug("post-purge refresh failed: {}", e.toString());
        }
        fireIndexChange();
    }

    /**
     * Kicks a single-flight resurrection pass over the tombstone registry,
     * paced to at most one pass per RESURRECT_PACE_MS so per-keystroke
     * searches cannot hammer the filesystem. All work happens on the
     * background cleaner thread — nothing blocks the search path.
     */
    private void kickResurrection() {
        if (closed || lastRootsSnapshot == null
                || org.abitware.docfinder.index.Tombstones.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastResurrectAt < RESURRECT_PACE_MS) {
            return;
        }
        if (!resurrectActive.compareAndSet(false, true)) {
            return;
        }
        lastResurrectAt = now;
        try {
            ghostCleaner.submit(this::drainResurrections);
        } catch (RuntimeException rejected) {
            resurrectActive.set(false);
        }
    }

    /**
     * One bounded pass: checks up to RESURRECT_BATCH tombstones (each stat
     * capped at the existence pool's timeout), aborts early after repeated
     * timeouts (dead source), and re-indexes paths whose files are back.
     * Still-absent paths keep their tombstones for a later pass.
     */
    private void drainResurrections() {
        try {
            List<Path> roots = lastRootsSnapshot;
            // Refresh when never loaded, when the last load failed (a
            // transient provider error must not freeze the snapshot
            // forever), or past TTL (source add/remove propagate lazily).
            boolean expired = System.nanoTime() - rootsLoadedAt
                    > TimeUnit.MILLISECONDS.toNanos(ROOTS_SNAPSHOT_TTL_MS);
            if (roots == null || rootsStale || expired) {
                List<Path> fresh = safeLoadRoots();
                if (fresh != null) {
                    lastRootsSnapshot = fresh;
                    rootsLoadedAt = System.nanoTime();
                    rootsStale = false;
                    roots = fresh;
                } else {
                    rootsStale = true; // keep retrying on the next pass
                    if (roots == null) {
                        return; // never loaded — nothing can be scoped
                    }
                    // else keep the last-good snapshot for this pass
                }
            }
            List<String> snap = org.abitware.docfinder.index.Tombstones.snapshot();
            int n = snap.size();
            if (n == 0) {
                return;
            }
            // Rotating cursor: resume AFTER the previously examined key and
            // wrap at the end — a head of still-missing files must never
            // starve tail entries out of every pass.
            int start = 0;
            String last = lastResurrectKey;
            if (last != null) {
                int at = snap.indexOf(last);
                start = (at < 0) ? 0 : (at + 1) % n;
            }
            List<String> batch = new ArrayList<>(RESURRECT_BATCH);
            int timeouts = 0;
            int checked = 0;
            for (int i = 0; i < n && checked < RESURRECT_BATCH
                    && timeouts < RESURRECT_TIMEOUT_GIVEUP; i++) {
                if (closed) {
                    return;
                }
                String p = snap.get((start + i) % n);
                lastResurrectKey = p;
                // Only roots currently configured are resurrection-eligible —
                // paths outside all sources stay tombstoned but cost no I/O.
                if (containingRoot(p, roots) == null) {
                    continue;
                }
                checked++;
                Boolean exists = statExists(p);
                if (exists == null) {
                    timeouts++;
                } else if (exists) {
                    batch.add(p);
                }
            }
            if (!batch.isEmpty() && !closed) {
                resurrectBatch(batch);
            }
        } finally {
            resurrectActive.set(false);
        }
    }

    /** Existence stat on the bounded pool with the per-check timeout. */
    private Boolean statExists(String pathStr) {
        java.util.concurrent.Future<Boolean> f;
        try {
            f = EXISTENCE_POOL.submit(() -> {
                try {
                    return Files.exists(Paths.get(pathStr));
                } catch (RuntimeException e) {
                    return Boolean.FALSE;
                }
            });
        } catch (RuntimeException rejected) {
            return null;
        }
        try {
            return f.get(PER_CHECK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException te) {
            f.cancel(true);
            return null;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return null;
        } catch (java.util.concurrent.ExecutionException | RuntimeException e) {
            return null;
        }
    }

    /** Re-indexes resurrected paths under the shared write lock. */
    private void resurrectBatch(List<String> batch) {
        IndexWriteCoordinator.run(() -> {
            if (closed) {
                return;
            }
            try (LuceneIndexer idx = new LuceneIndexer(indexDir, indexSettings)) {
                for (String s : batch) {
                    if (closed) {
                        return;
                    }
                    try {
                        Path p = Paths.get(s);
                        if (Files.exists(p)) {
                            idx.upsertFile(p);
                            org.abitware.docfinder.index.Tombstones.remove(s);
                            verdictCache.remove(s); // clear any stale GHOST verdict
                        }
                    } catch (RuntimeException ignore) {
                    }
                }
                idx.commit();
            } catch (IOException | RuntimeException e) {
                log.debug("resurrect write failed: {}", e.toString());
            }
        });
        try {
            searcherManager.maybeRefresh();
        } catch (IOException | RuntimeException e) {
            log.debug("post-resurrect refresh failed: {}", e.toString());
        }
        fireIndexChange();
    }

    /**
     * Materialize ScoreDocs into SearchResults. When tierSort is set the slice
     * is re-ordered by (-score, name length, path) — the tie-break honestly
     * applies only within the fetched window (D4). Match source is explained
     * against the original name/content sub-queries (R1-03), or via the
     * legacy single-query explanation path when nameQ/contentQ are null.
     * With a non-null RefillState each document's path is stat-checked once:
     * confirmed-deleted paths are skipped and recorded for async purge, and
     * re-fetched hits are deduplicated across refill rounds.
     */
    private void appendResults(IndexSearcher searcher, List<SearchResult> out, TopDocs top,
                               Query userQuery, List<Query> nameClauses,
                               Query nameQ, Query contentQ, boolean tierSort,
                               RefillState st) throws IOException {
        List<SearchResult> slice = new ArrayList<>(top.scoreDocs.length);
        Query explainQuery = (userQuery != null || (nameClauses != null && !nameClauses.isEmpty()))
                ? buildExplainQuery(userQuery, nameClauses) : null;
        for (ScoreDoc sd : top.scoreDocs) {
            if (st != null && isStale(st)) {
                break; // a newer search superseded this one — stop spending I/O
            }
            Document d = searcher.doc(sd.doc);
            boolean isFolder = KIND_FOLDER.equalsIgnoreCase(d.get(FIELD_KIND));
            String name = d.get(FIELD_NAME);
            String path = d.get(FIELD_PATH);
            if (st != null && path != null) {
                PathVerdict pv = verifyPath(path, st);
                if (pv == PathVerdict.GHOST) {
                    st.addGhost(path);
                    continue;
                }
                if (pv != PathVerdict.ALIVE) {
                    // Unverifiable entries (offline root, permission, slow
                    // I/O) are NOT displayed and must not consume the
                    // verified result quota — st.hadUncertain already flags
                    // the outcome so the UI can say results are incomplete.
                    continue;
                }
            }
            long ctime = getStoredLong(d, FIELD_CTIME_DISPLAY);
            long atime = getStoredLong(d, FIELD_ATIME_DISPLAY);
            long size = getStoredLong(d, FIELD_SIZE);
            if (isFolder) size = 0L;

            String match;
            if (isFolder) {
                match = "folder";
            } else if (nameQ != null || contentQ != null) {
                match = matchFromBranches(searcher, nameQ, contentQ, sd.doc);
            } else {
                match = detectMatchType(searcher, explainQuery, sd.doc);
            }
            slice.add(new SearchResult(name, path, sd.score, ctime, atime, match, size, isFolder));
        }
        if (tierSort) {
            slice.sort((a, b) -> {
                int c = Float.compare(b.score, a.score);
                if (c != 0) return c;
                c = Integer.compare(a.name == null ? Integer.MAX_VALUE : a.name.length(),
                        b.name == null ? Integer.MAX_VALUE : b.name.length());
                if (c != 0) return c;
                return String.CASE_INSENSITIVE_ORDER.compare(
                        a.path == null ? "" : a.path, b.path == null ? "" : b.path);
            });
        }
        out.addAll(slice);
    }

    /**
     * Match source from the two original keyword sub-queries — never the
     * MUST_NOT-decorated content-only retrieval query (R1-03).
     */
    private String matchFromBranches(IndexSearcher searcher, Query nameQ, Query contentQ, int docId) {
        boolean n = false, c = false;
        try {
            if (nameQ != null) n = searcher.explain(nameQ, docId).isMatch();
        } catch (Exception ignore) {
        }
        try {
            if (contentQ != null) c = searcher.explain(contentQ, docId).isMatch();
        } catch (Exception ignore) {
        }
        if (n && c) return "name+content";
        if (n) return "name";
        if (c) return "content";
        return "";
    }

    private void addKindFilter(BooleanQuery.Builder root, SearchScope scope) {
        if (scope == SearchScope.FOLDER) {
            root.add(new TermQuery(new Term(FIELD_KIND, KIND_FOLDER)), BooleanClause.Occur.MUST);
        } else {
            // Type filtering inside the query (before Top-N) so excluded
            // high-score folders cannot consume the limit (D4).
            root.add(new TermQuery(new Term(FIELD_KIND, KIND_FOLDER)),
                    BooleanClause.Occur.MUST_NOT);
        }
    }

    /* ---------------- literal name-substring path (D2/D3) ----------------- */

    private static final int MAX_LITERAL_TOKENS = 16;
    private static final int MAX_LITERAL_TOKEN_CODEPOINTS = 64;

    /**
     * Literal classification: only plain-FUZZY inputs without advanced syntax
     * or boolean-operator tokens take the name-substring path, and only for
     * NAME/FOLDER/ALL scopes (CONTENT, EXACT and explicit advanced syntax are
     * frozen paths — R1 routing boundary).
     */
    private List<String> literalTokensIfEligible(String qRest, SearchScope scope,
                                                 MatchMode matchMode, boolean advanced) {
        if (advanced || matchMode != MatchMode.FUZZY || qRest == null || qRest.isEmpty()
                || hasBooleanOperatorToken(qRest) || qRest.indexOf('(') >= 0) {
            return null;
        }
        if (scope != SearchScope.NAME && scope != SearchScope.FOLDER && scope != SearchScope.ALL) {
            return null;
        }
        List<String> toks = splitLiteralTokens(qRest);
        if (toks.isEmpty()) {
            return null;
        }
        if (toks.size() > MAX_LITERAL_TOKENS) {
            throw new SearchException(SearchException.Code.INVALID_QUERY,
                    "invalid query syntax: too many words (max " + MAX_LITERAL_TOKENS + ")");
        }
        for (String t : toks) {
            if (t.codePointCount(0, t.length()) > MAX_LITERAL_TOKEN_CODEPOINTS) {
                throw new SearchException(SearchException.Code.INVALID_QUERY,
                        "invalid query syntax: word too long (max " + MAX_LITERAL_TOKEN_CODEPOINTS + " chars)");
            }
        }
        return toks;
    }

    /** Split on Unicode whitespace (Character.isWhitespace || isSpaceChar). */
    private List<String> splitLiteralTokens(String q) {
        List<String> toks = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int[] cps = q.codePoints().toArray();
        for (int cp : cps) {
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                if (cur.length() > 0) {
                    toks.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.appendCodePoint(cp);
            }
        }
        if (cur.length() > 0) {
            toks.add(cur.toString());
        }
        return toks;
    }

    /**
     * Boolean operators count as advanced syntax only as independent lexical
     * tokens (whole-word, uppercase as the classic parser requires) —
     * "band"/"SANDY" must not be misclassified (D3).
     */
    private boolean hasBooleanOperatorToken(String qRest) {
        if (qRest == null || qRest.isEmpty()) return false;
        for (String t : splitLiteralTokens(qRest)) {
            if ("AND".equals(t) || "OR".equals(t) || "NOT".equals(t)) return true;
        }
        return false;
    }

    /**
     * One word's three constant tiers on name_raw (lowercased stored form):
     * exact=4, prefix=3, substring=2 via DisjunctionMax(tie=0). Multi-word
     * conditions AND across words; the document name score is the sum of the
     * per-word tier constants (D2).
     */
    private Query buildNameCondition(List<String> tokens) {
        BooleanQuery.Builder all = new BooleanQuery.Builder();
        for (String tok : tokens) {
            String t = tok.toLowerCase(Locale.ROOT);
            List<Query> tiers = new ArrayList<>(3);
            tiers.add(new BoostQuery(new ConstantScoreQuery(
                    new TermQuery(new Term(FIELD_NAME_RAW, t))), 4.0f));
            tiers.add(new BoostQuery(new ConstantScoreQuery(
                    new PrefixQuery(new Term(FIELD_NAME_RAW, t))), 3.0f));
            tiers.add(new BoostQuery(new ConstantScoreQuery(
                    new WildcardQuery(new Term(FIELD_NAME_RAW, "*" + escapeWildcard(t) + "*"))), 2.0f));
            all.add(new DisjunctionMaxQuery(tiers, 0.0f), BooleanClause.Occur.MUST);
        }
        return all.build();
    }

    /** Escape wildcard meta characters for WildcardQuery (backslash is the escape char). */
    private static String escapeWildcard(String literal) {
        StringBuilder sb = new StringBuilder(literal.length() + 4);
        for (int i = 0; i < literal.length(); i++) {
            char c = literal.charAt(i);
            if (c == '\\' || c == '*' || c == '?') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * ALL literal content branch: literal-escape the raw input, then reuse the
     * existing content-field parser/analyzer rules (R1-02). Returns null when
     * analysis yields no terms — the content list simply has no hits.
     */
    private Query buildLiteralContentQuery(String qRest) throws ParseException {
        String escaped = QueryParser.escape(qRest);
        MultiFieldQueryParser parser = new MultiFieldQueryParser(CONTENT_FIELDS, queryAnalyzer);
        Query q = parser.parse(escaped);
        if (q == null || (q instanceof BooleanQuery && ((BooleanQuery) q).clauses().isEmpty())) {
            return null;
        }
        return q;
    }

    @Override
    public void notifyIndexCommit() {
        // Any real index change invalidates negative existence verdicts: a
        // restored file upserted by watch/poller/resurrection must not stay
        // hidden behind a cached GHOST for the TTL window.
        verdictCache.clear();
        // Proactively refresh SearcherManager after commit to make newly-written documents immediately visible to next search
        try {
            searcherManager.maybeRefresh();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void close() {
        synchronized (scopePublishLock) {
            closed = true;
            scopeSupportCache.clear();
            scopeListenerKeys.clear();
        }
        // Cancel pending work: drop the purge backlog, stop the drain and the
        // existence pool. Awaiting an in-flight purge must never happen on the
        // EDT — the purge thread is a daemon and re-checks `closed` under the
        // write lock before every delete, so it exits on its own shortly.
        synchronized (ghostDrainSet) {
            ghostDrainSet.clear();
        }
        org.abitware.docfinder.index.Tombstones.flush();
        resurrectTimer.shutdownNow();
        ghostCleaner.shutdown();
        // EXISTENCE_POOL is a shared JVM resource — a closed service must
        // not shut it down (other instances still submit). Stale requests
        // stop referencing it via their session-gen checks; in-flight stats
        // are bounded and expire on the pool's own idle timeout.
        if (!javax.swing.SwingUtilities.isEventDispatchThread()) {
            try {
                if (!ghostCleaner.awaitTermination(2, TimeUnit.SECONDS)) {
                    ghostCleaner.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                ghostCleaner.shutdownNow();
            }
        } else {
            ghostCleaner.shutdownNow();
        }
        try {
            if (searcherManager != null) {
                searcherManager.close();
            }
            if (directory != null) {
                directory.close();
            }
        } catch (IOException e) {
            // TODO: log this error
            e.printStackTrace();
        }
    }

    private Query buildUserQuery(String qRest, SearchScope scope, MatchMode matchMode, boolean advanced) throws ParseException {
        if (qRest == null) qRest = "";
        if (qRest.isEmpty()) return null;

        if (matchMode == MatchMode.EXACT && !advanced) {
            return buildExactQuery(qRest, scope);
        }

        String[] fields = fieldsForScope(scope);
        Map<String, Float> boosts = new HashMap<>();
        for (String field : fields) {
            if (FIELD_NAME.equals(field)) {
                boosts.put(field, 2.0f);
            } else {
                boosts.put(field, 1.2f);
            }
        }

        MultiFieldQueryParser parser = new MultiFieldQueryParser(fields, queryAnalyzer, boosts);
        parser.setAllowLeadingWildcard(true);
        Query parsed = parser.parse(qRest);

        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        boolean prefixEligible = includesName(scope) && matchMode == MatchMode.FUZZY;
        boolean allowPrefix = false;
        if (prefixEligible) {
            allowPrefix = shouldAddNamePrefix(qRest);
            if (!allowPrefix && scope == SearchScope.FOLDER) {
                allowPrefix = allowFolderPrefix(qRest);
            }
        }

        if (allowPrefix) {
            String lower = qRest.toLowerCase(Locale.ROOT);
            Query namePrefixRaw = new PrefixQuery(new Term(FIELD_NAME_RAW, lower));
            Query namePrefixText = new PrefixQuery(new Term(FIELD_NAME, lower));
            BooleanQuery.Builder prefixEither = new BooleanQuery.Builder();
            prefixEither.add(namePrefixRaw, BooleanClause.Occur.SHOULD);
            prefixEither.add(namePrefixText, BooleanClause.Occur.SHOULD);
            if (scope == SearchScope.FOLDER) {
                Query containsRaw = new WildcardQuery(new Term(FIELD_NAME_RAW, "*" + lower + "*"));
                prefixEither.add(containsRaw, BooleanClause.Occur.SHOULD);
            }
            Query namePrefix = prefixEither.build();
            if (scope == SearchScope.NAME || scope == SearchScope.FOLDER) {
                BooleanQuery.Builder either = new BooleanQuery.Builder();
                either.add(parsed, BooleanClause.Occur.SHOULD);
                either.add(namePrefix, BooleanClause.Occur.SHOULD);
                builder.add(either.build(), BooleanClause.Occur.MUST);
            } else {
                builder.add(parsed, BooleanClause.Occur.MUST);
                builder.add(namePrefix, BooleanClause.Occur.SHOULD);
            }
        } else {
            builder.add(parsed, BooleanClause.Occur.MUST);
        }

        return builder.build();
    }

    private Query buildExactQuery(String query, SearchScope scope) throws ParseException {
        String escaped = QueryParser.escape(query);
        BooleanQuery.Builder builder = new BooleanQuery.Builder();

        if (includesName(scope)) {
            builder.add(new TermQuery(new Term(FIELD_NAME_RAW, query.toLowerCase(Locale.ROOT))), BooleanClause.Occur.SHOULD);
            QueryParser qp = new QueryParser(FIELD_NAME, queryAnalyzer);
            qp.setAllowLeadingWildcard(false);
            builder.add(qp.parse("\"" + escaped + "\""), BooleanClause.Occur.SHOULD);
        }

        if (includesContent(scope)) {
            BooleanQuery.Builder content = new BooleanQuery.Builder();
            for (String field : CONTENT_FIELDS) {
                QueryParser qp = new QueryParser(field, queryAnalyzer);
                qp.setAllowLeadingWildcard(false);
                content.add(qp.parse("\"" + escaped + "\""), BooleanClause.Occur.SHOULD);
            }
            builder.add(content.build(), BooleanClause.Occur.SHOULD);
        }

        BooleanQuery exact = builder.build();
        if (exact.clauses().isEmpty()) {
            return null;
        }
        return exact;
    }

    private boolean includesName(SearchScope scope) {
        return scope == SearchScope.ALL || scope == SearchScope.NAME || scope == SearchScope.FOLDER;
    }

    private boolean includesContent(SearchScope scope) {
        return scope == SearchScope.ALL || scope == SearchScope.CONTENT;
    }

    private String[] fieldsForScope(SearchScope scope) {
        switch (scope) {
            case NAME:
            case FOLDER:
                return new String[] { FIELD_NAME };
            case CONTENT:
                return CONTENT_FIELDS;
            case ALL:
            default:
                return new String[] { FIELD_NAME, CONTENT_FIELDS[0], CONTENT_FIELDS[1], CONTENT_FIELDS[2] };
        }
    }

    private boolean allowFolderPrefix(String qRest) {
        if (qRest == null || qRest.isEmpty()) return false;
        if (qRest.contains(":") || qRest.contains(" ") || qRest.endsWith("*")) return false;
        for (int i = 0; i < qRest.length(); i++) {
            char c = qRest.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == '-')) {
                return false;
            }
        }
        return true;
    }

    private boolean shouldAddNamePrefix(String qRest) {
        boolean looksAsciiWord = qRest.matches("^[\\p{Alnum}_.\\-]+$");
        boolean hasField = qRest.contains(":");
        boolean hasSpace = qRest.contains(" ");
        boolean hasWildcard = qRest.endsWith("*");
        return !hasField && !hasSpace && !hasWildcard && looksAsciiWord && qRest.length() >= 2;
    }

    private boolean hasAdvancedSyntax(String input) {
        if (input == null || input.isEmpty()) return false;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == ':' || c == '*' || c == '?' || c == '"' || c == ')' || c == '!') {
                return true;
            }
        }
        return false;
    }

    private void addNameWildcardMust(BooleanQuery.Builder root, List<String> patterns,
                                     List<Query> explainCollector) {
        if (patterns == null || patterns.isEmpty()) return;

        for (String pat : patterns) {
            if (pat == null) continue;
            String p = pat.toLowerCase(Locale.ROOT).trim();
            if (p.isEmpty()) continue;

            boolean hasWildcard = (p.indexOf('*') >= 0) || (p.indexOf('?') >= 0);

            int dot = p.lastIndexOf('.');
            if (p.startsWith("*") && dot >= 0 && dot < p.length() - 1
                    && p.indexOf('*', dot) < 0 && p.indexOf('?', dot) < 0) {
                String ext = p.substring(dot + 1);
                if (ext.matches("[\\p{Alnum}_-]+")) {
                    Query extQuery = new TermQuery(new Term(FIELD_EXT, ext));
                    root.add(extQuery, BooleanClause.Occur.MUST);
                    explainCollector.add(extQuery);
                }
            }

            Query qPrimary = hasWildcard
                    ? new WildcardQuery(new Term(FIELD_NAME_RAW, p))
                    : new TermQuery(new Term(FIELD_NAME_RAW, p));

            Query qFallback = null;
            if (!hasWildcard) {
                try {
                    QueryParser qp = new QueryParser(FIELD_NAME, queryAnalyzer);
                    qp.setAllowLeadingWildcard(true);
                    qFallback = qp.parse("\"" + p.replace("\"", "\\\"") + "\"");
                } catch (Exception ignore) {
                }
            }

            if (qFallback != null) {
                BooleanQuery.Builder either = new BooleanQuery.Builder();
                either.add(qPrimary, BooleanClause.Occur.SHOULD);
                either.add(qFallback, BooleanClause.Occur.SHOULD);
                Query eitherQ = either.build();
                root.add(eitherQ, BooleanClause.Occur.MUST);
                explainCollector.add(eitherQ);
            } else {
                root.add(qPrimary, BooleanClause.Occur.MUST);
                explainCollector.add(qPrimary);
            }
        }
    }

    /**
     * Query used only for match-source explanation: the user keyword query
     * plus explicit name:/wildcard name clauses. Directory/type/time filters
     * are deliberately excluded (D4/QA24-03).
     */
    private Query buildExplainQuery(Query userQuery, List<Query> nameClauses) {
        BooleanQuery.Builder eb = new BooleanQuery.Builder();
        boolean any = false;
        if (userQuery != null
                && !(userQuery instanceof BooleanQuery && ((BooleanQuery) userQuery).clauses().isEmpty())) {
            eb.add(userQuery, BooleanClause.Occur.MUST);
            any = true;
        }
        for (Query c : nameClauses) {
            eb.add(c, BooleanClause.Occur.MUST);
            any = true;
        }
        return any ? eb.build() : null;
    }

    /** 应用扩展名与时间范围过滤（沿用你的 FilterState） */
    private void addFilters(BooleanQuery.Builder root, FilterState filter) {
        if (filter != null && filter.exts != null && !filter.exts.isEmpty()) {
            BooleanQuery.Builder e = new BooleanQuery.Builder();
            for (String ex : filter.exts) {
                if (ex == null || ex.isEmpty()) continue;
                e.add(new TermQuery(new Term(FIELD_EXT, ex.toLowerCase(Locale.ROOT))),
                        BooleanClause.Occur.SHOULD);
            }
            root.add(e.build(), BooleanClause.Occur.MUST);
        }
        if (filter != null && (filter.fromEpochMs != null || filter.toEpochMs != null)) {
            long from = (filter.fromEpochMs == null) ? Long.MIN_VALUE : filter.fromEpochMs;
            long to   = (filter.toEpochMs   == null) ? Long.MAX_VALUE : filter.toEpochMs;
            root.add(LongPoint.newRangeQuery("mtime_l", from, to), BooleanClause.Occur.MUST);
        }
    }

    private long getStoredLong(Document doc, String field) {
        StoredField stored = (StoredField) doc.getField(field);
        return stored == null ? 0L : stored.numericValue().longValue();
    }

    private String detectMatchType(IndexSearcher searcher, Query q, int docId) {
        try {
            Explanation exp = searcher.explain(q, docId);
            boolean[] flags = new boolean[2]; // [0]=name, [1]=content
            walk(exp, flags);
            if (flags[0] && flags[1]) return "name+content";
            if (flags[0]) return "name";
            if (flags[1]) return "content";
        } catch (Exception ignore) {
        }
        return "";
    }
    
    private void walk(Explanation e, boolean[] f) {
        String desc = e.getDescription().toLowerCase(Locale.ROOT);
        // "name_raw" is the untokenized name field used by explicit name:
        // patterns and prefix matching; it is still a name-source hit.
        if (desc.contains("name:") || desc.contains("name_raw:") || desc.contains("ext:")) f[0] = true;
        if (desc.contains("content:") || desc.contains("content_zh:") || desc.contains("content_ja:")) f[1] = true;
        for (Explanation d : e.getDetails()) walk(d, f);
    }
    
    private static class NamePreprocess {
        final String qRest;
        final List<String> nameWildcards;
        NamePreprocess(String qRest, List<String> pats) { this.qRest = qRest; this.nameWildcards = pats; }
    }
    
    private NamePreprocess extractNameWildcards(String qRaw) {
        String q = qRaw == null ? "" : qRaw.trim();
        Pattern NAME_FILTER = Pattern.compile("(?i)(?:^|\\s)name:(\"[^\"]+\"|'[^']+'|\\S+)");
        Matcher m = NAME_FILTER.matcher(q);

        List<String> pats = new ArrayList<>();
        StringBuffer rest = new StringBuffer();
        while (m.find()) {
            String token = m.group(1);
            if ((token.startsWith("\"") && token.endsWith("\"")) || (token.startsWith("'") && token.endsWith("'"))) {
                token = token.substring(1, token.length() - 1);
            }
            pats.add(token);
            m.appendReplacement(rest, " ");
        }
        m.appendTail(rest);
        String qRest = rest.toString().trim();

        if (pats.isEmpty() && (q.contains("*") || q.contains("?")) && !q.contains(":")) {
            pats.add(q);
            qRest = "";
        }
        return new NamePreprocess(qRest, pats);
    }
}
