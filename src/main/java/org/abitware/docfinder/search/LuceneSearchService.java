package org.abitware.docfinder.search;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    public LuceneSearchService(Path indexDir) throws IOException {
        this(indexDir, new IndexSettings());
    }

    public LuceneSearchService(Path indexDir, IndexSettings settings) throws IOException {
        this(indexDir, settings, defaultProvider());
    }

    public LuceneSearchService(Path indexDir, IndexSettings settings,
                               SourceRootProvider sourceRootProvider) throws IOException {
        this.indexDir = indexDir;
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
     * D4: normalize → load snapshot → coverage check.
     */
    private String resolveScopedDirectory(String directoryPath) throws IOException {
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
        if (request == null) return new SearchOutcome(out, TriState.FALSE);

        String rawQuery = request.query == null ? "" : request.query.trim();
        // Blank query short-circuits before directory/source/syntax checks (D3).
        if (rawQuery.isEmpty()) return new SearchOutcome(out, TriState.FALSE);

        FilterState filter = request.filter;
        SearchScope scope = request.scope == null ? SearchScope.ALL : request.scope;
        MatchMode matchMode = request.matchMode == null ? MatchMode.FUZZY : request.matchMode;

        String scopedDir;
        try {
            scopedDir = resolveScopedDirectory(request.directoryPath);
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
                        scope, filter, scopedDir, limit, fetchLimit);
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

                TopDocs top = searcher.search(root.build(), fetchLimit);
                boolean tierSort = literalTokens != null;
                appendResults(searcher, out, top, userQuery, nameClauses, null, null, tierSort);
                hasMore = (out.size() > limit) ? TriState.TRUE : TriState.FALSE;
                if (out.size() > limit) {
                    out.subList(limit, out.size()).clear();
                }
            }
            return new SearchOutcome(out, hasMore);
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
                                      int limit, int fetchLimit) throws IOException, ParseException {
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
        TopDocs nameTop = searcher.search(nameRoot.build(), fetchLimit);
        appendResults(searcher, out, nameTop, null, null, nameCond, contentQ, true);

        // List 2: content-only hits fill the remaining quota; MUST_NOT keeps
        // the lists disjoint so name+content docs live only in list 1.
        if (out.size() < fetchLimit && contentQ != null) {
            BooleanQuery.Builder contentRoot = new BooleanQuery.Builder();
            contentRoot.add(contentQ, BooleanClause.Occur.MUST);
            contentRoot.add(nameCond, BooleanClause.Occur.MUST_NOT);
            addNameWildcardMust(contentRoot, np.nameWildcards, new ArrayList<Query>());
            addKindFilter(contentRoot, scope);
            addFilters(contentRoot, filter);
            if (scopedDir != null) {
                addDirectoryFilter(contentRoot, scopedDir);
            }
            TopDocs contentTop = searcher.search(contentRoot.build(), fetchLimit - out.size());
            appendResults(searcher, out, contentTop, null, null, nameCond, contentQ, false);
        }

        TriState hasMore = (out.size() > limit) ? TriState.TRUE : TriState.FALSE;
        if (out.size() > limit) {
            out.subList(limit, out.size()).clear();
        }
        return hasMore;
    }

    /**
     * Materialize ScoreDocs into SearchResults. When tierSort is set the slice
     * is re-ordered by (-score, name length, path) — the tie-break honestly
     * applies only within the fetched window (D4). Match source is explained
     * against the original name/content sub-queries (R1-03), or via the
     * legacy single-query explanation path when nameQ/contentQ are null.
     */
    private void appendResults(IndexSearcher searcher, List<SearchResult> out, TopDocs top,
                               Query userQuery, List<Query> nameClauses,
                               Query nameQ, Query contentQ, boolean tierSort) throws IOException {
        List<SearchResult> slice = new ArrayList<>(top.scoreDocs.length);
        Query explainQuery = (userQuery != null || (nameClauses != null && !nameClauses.isEmpty()))
                ? buildExplainQuery(userQuery, nameClauses) : null;
        for (ScoreDoc sd : top.scoreDocs) {
            Document d = searcher.doc(sd.doc);
            boolean isFolder = KIND_FOLDER.equalsIgnoreCase(d.get(FIELD_KIND));
            String name = d.get(FIELD_NAME);
            String path = d.get(FIELD_PATH);
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
