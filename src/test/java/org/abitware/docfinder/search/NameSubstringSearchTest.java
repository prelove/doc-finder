package org.abitware.docfinder.search;

import org.abitware.docfinder.index.IndexSettings;
import org.abitware.docfinder.index.LuceneIndexer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * B1/B2/B3 contract: literal middle-substring name search (single-char,
 * CJK, supplementary-plane, symbols), multi-word AND, fixed exact/prefix/
 * substring tier ordering, the ALL dual-list (name list first, exclusive
 * content-only list after), match-source explanations from the original
 * keyword sub-queries, tri-state hasMore via limit+1 probing, and query
 * limits. Real temporary index via LuceneIndexer → LuceneSearchService.
 */
@Execution(ExecutionMode.SAME_THREAD)
class NameSubstringSearchTest {

    private static final String EMOJI = new String(Character.toChars(0x1F4CA));
    private static final String ZHIFU = "支付";
    private static final String ZHIFU_FILE = "支付记录.txt";
    private static final String EMOJI_FILE = EMOJI + "报表.txt";
    private static final String BAO = "报";

    @TempDir
    Path fixtureRoot;

    private Path source;
    private Path index;
    private LuceneSearchService service;

    @BeforeEach
    void setUp() throws Exception {
        source = fixtureRoot.resolve("source");
        index = fixtureRoot.resolve("index");
        Files.createDirectories(source.resolve("sub"));
        Files.createDirectories(source.resolve("reports"));
        Files.createDirectories(source.resolve("zz"));

        // name hits only (content never mentions "report" unless noted)
        write(source.resolve("report"), "this file mentions report keyword too"); // exact tier, name+content
        write(source.resolve("report-q4.txt"), "lorem ipsum");
        write(source.resolve("report-summary.txt"), "lorem ipsum");
        write(source.resolve("AnnualReport2026.PDF"), "lorem ipsum");
        write(source.resolve("spring-core.txt"), "lorem ipsum");
        write(source.resolve(ZHIFU_FILE), "lorem ipsum");
        write(source.resolve(EMOJI_FILE), "lorem ipsum");
        write(source.resolve("beta-notes.txt"), "report findings inside");
        write(source.resolve("foo and bar notes.txt"), "lorem ipsum"); // EXACT phrase hit
        write(source.resolve("bar foo.txt"), "lorem ipsum");          // same words, wrong order
        write(source.resolve("sub").resolve("report-sub.txt"), "lorem ipsum");
        write(source.resolve("reports").resolve("inside.txt"), "lorem ipsum");
        for (int i = 1; i <= 5; i++) {
            write(source.resolve("zz").resolve(String.format("zz-%02d.txt", i)), "lorem ipsum");
        }

        IndexSettings settings = new IndexSettings();
        try (LuceneIndexer indexer = new LuceneIndexer(index, settings)) {
            indexer.indexFolder(source);
        }
        SourceRootProvider provider = () -> Collections.singletonList(source);
        service = new LuceneSearchService(index, settings, provider);
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.close();
        }
    }

    private static void write(Path p, String content) throws Exception {
        Files.write(p, content.getBytes("UTF-8"));
    }

    private SearchOutcome detailed(String q, int limit, SearchScope scope, MatchMode mode) {
        return service.searchDetailed(new SearchRequest(q, limit, null, scope, mode));
    }

    private List<SearchResult> search(String q, int limit, SearchScope scope) {
        return detailed(q, limit, scope, MatchMode.FUZZY).results;
    }

    private static List<String> names(List<SearchResult> rs) {
        java.util.List<String> out = new java.util.ArrayList<>(rs.size());
        for (SearchResult r : rs) out.add(r.name);
        return out;
    }

    // ── literal substring matching ─────────────────────────────────────────

    @Test
    void shouldMatchMiddleSubstringOnName() {
        List<SearchResult> rs = search("report", 50, SearchScope.NAME);
        assertTrue(names(rs).contains("AnnualReport2026.PDF"),
                "literal token must match a middle fragment of the name");
    }

    @Test
    void shouldMatchCjkSingleCharAndSupplementaryPlane() {
        assertEquals(Collections.singletonList(ZHIFU_FILE), names(search(ZHIFU, 50, SearchScope.NAME)));
        assertEquals(Collections.singletonList(EMOJI_FILE), names(search(BAO, 50, SearchScope.NAME)));
        assertEquals(Collections.singletonList(EMOJI_FILE), names(search(EMOJI, 50, SearchScope.NAME)));
    }

    @Test
    void shouldMatchCaseInsensitivelyAndSymbols() {
        assertTrue(names(search("ANNUALREPORT", 50, SearchScope.NAME))
                .contains("AnnualReport2026.PDF"), "case-insensitive literal match");
        assertEquals(Collections.singletonList("spring-core.txt"),
                names(search("spring-core", 50, SearchScope.NAME)),
                "symbol token must be treated literally, not as syntax");
    }

    @Test
    void shouldRequireEveryWordToMatchTheName() {
        List<SearchResult> rs = search("annual report", 50, SearchScope.NAME);
        assertEquals(Collections.singletonList("AnnualReport2026.PDF"), names(rs));

        rs = search("report 2026", 50, SearchScope.NAME);
        assertEquals(Collections.singletonList("AnnualReport2026.PDF"), names(rs),
                "NAME must not return a doc matching only some words");
    }

    // ── tier ordering ──────────────────────────────────────────────────────

    @Test
    void shouldOrderExactBeforePrefixBeforeSubstring() {
        List<SearchResult> rs = search("report", 50, SearchScope.NAME);
        List<String> ns = names(rs);
        assertEquals("report", ns.get(0), "full-name match must rank first");
        assertEquals("AnnualReport2026.PDF", ns.get(ns.size() - 1),
                "middle-substring hit must rank below exact/prefix hits");
        int exactIdx = ns.indexOf("report");
        int q4Idx = ns.indexOf("report-q4.txt");
        int summaryIdx = ns.indexOf("report-summary.txt");
        int subIdx = ns.indexOf("report-sub.txt");
        int middleIdx = ns.indexOf("AnnualReport2026.PDF");
        assertTrue(exactIdx >= 0 && q4Idx > exactIdx && summaryIdx > q4Idx
                        && middleIdx > summaryIdx && middleIdx > subIdx,
                "tier order violated: " + ns);
        assertEquals(4.0f, rs.get(exactIdx).score, 0.001f, "exact tier constant");
        assertEquals(3.0f, rs.get(q4Idx).score, 0.001f, "prefix tier constant");
        assertEquals(2.0f, rs.get(middleIdx).score, 0.001f, "substring tier constant");
    }

    // ── ALL dual-list ──────────────────────────────────────────────────────

    @Test
    void shouldListNameHitsBeforeExclusiveContentHitsInAll() {
        List<SearchResult> rs = search("report", 50, SearchScope.ALL);
        List<String> ns = names(rs);
        assertTrue(ns.contains("beta-notes.txt"), "content-only hit must survive in ALL");
        assertTrue(ns.contains("AnnualReport2026.PDF"), "name hit must survive in ALL");
        assertTrue(ns.indexOf("beta-notes.txt") > ns.indexOf("AnnualReport2026.PDF"),
                "name list must precede the exclusive content list: " + ns);
        for (SearchResult r : rs) {
            assertFalse(r.directory, "folder docs must not leak into ALL");
        }
        assertEquals(1, Collections.frequency(ns, "report"),
                "a name+content doc must appear exactly once (disjoint lists)");
    }

    @Test
    void shouldExplainMatchSourceFromOriginalSubQueries() {
        List<SearchResult> rs = search("report", 50, SearchScope.ALL);
        String matchReport = null, matchBeta = null, matchMiddle = null;
        for (SearchResult r : rs) {
            if ("report".equals(r.name)) matchReport = r.match;
            if ("beta-notes.txt".equals(r.name)) matchBeta = r.match;
            if ("AnnualReport2026.PDF".equals(r.name)) matchMiddle = r.match;
        }
        assertEquals("name+content", matchReport,
                "doc matching both branches explained against original sub-queries");
        assertEquals("content", matchBeta,
                "content-only hit must not be explained by the MUST_NOT name clause");
        assertEquals("name", matchMiddle);
    }

    // ── frozen paths ───────────────────────────────────────────────────────

    @Test
    void shouldKeepContentScopeExcludingNameOnlyHits() {
        List<SearchResult> rs = search("report", 50, SearchScope.CONTENT);
        assertTrue(names(rs).contains("beta-notes.txt"));
        assertFalse(names(rs).contains("AnnualReport2026.PDF"),
                "CONTENT must not return name-only hits");
    }

    @Test
    void shouldKeepExactFrozenFromSubstring() {
        List<SearchResult> fuzzy = search("annualreport", 50, SearchScope.NAME);
        assertTrue(fuzzy.stream().anyMatch(r -> "AnnualReport2026.PDF".equals(r.name)),
                "FUZZY literal substring must hit the middle fragment");
        List<SearchResult> exact = detailed("annualreport", 50, SearchScope.NAME, MatchMode.EXACT).results;
        assertTrue(exact.stream().noneMatch(r -> "AnnualReport2026.PDF".equals(r.name)),
                "EXACT must not substring-match the middle fragment");
        List<SearchResult> exactFull = detailed("annualreport2026.pdf", 50, SearchScope.NAME, MatchMode.EXACT).results;
        assertTrue(exactFull.stream().anyMatch(r -> "AnnualReport2026.PDF".equals(r.name)),
                "EXACT full-name term must still hit");
    }

    @Test
    void shouldKeepExactFrozenForBooleanAndParenInputs() {
        // Regression: the new literal classification must not steer EXACT —
        // 'foo AND bar' stays the frozen phrase semantics (hit order matters),
        // not the parsed boolean query which also matches 'bar foo.txt'.
        List<SearchResult> rs = detailed("foo AND bar", 50, SearchScope.ALL, MatchMode.EXACT).results;
        assertTrue(names(rs).contains("foo and bar notes.txt"),
                "EXACT phrase must hit consecutive-word name");
        assertFalse(names(rs).contains("bar foo.txt"),
                "parsed-boolean result must not appear under frozen EXACT");

        // '(' must not make EXACT error — frozen path treats it literally and
        // still returns the report-named hits (old behavior, Codex repro).
        List<SearchResult> paren = detailed("report (", 50, SearchScope.NAME, MatchMode.EXACT).results;
        assertTrue(names(paren).contains("report"),
                "EXACT 'report (' must keep returning the report file, not error");
    }

    @Test
    void shouldKeepBooleanAndParenOutOfLiteralPath() {
        // FUZZY 'report (' was INVALID_QUERY before the change (parser path).
        SearchException e = assertThrows(SearchException.class,
                () -> search("report (", 50, SearchScope.NAME));
        assertEquals(SearchException.Code.INVALID_QUERY, e.getCode(),
                "FUZZY 'report (' must keep the parser/INVALID_QUERY contract");
    }

    @Test
    void shouldKeepExplicitWildcardAndFolderScope() {
        List<SearchResult> wc = search("*report*", 50, SearchScope.NAME);
        assertTrue(names(wc).contains("AnnualReport2026.PDF"),
                "explicit name wildcard path preserved");

        List<SearchResult> folder = search("report", 50, SearchScope.FOLDER);
        assertEquals(1, folder.size());
        assertTrue(folder.get(0).directory);
        assertEquals("reports", folder.get(0).name);
    }

    // ── directory scope ────────────────────────────────────────────────────

    @Test
    void shouldApplyDirectoryScopeToLiteralPath() {
        String sub = org.abitware.docfinder.util.PathScope
                .normalizeDirectory(source.resolve("sub").toString());
        List<SearchResult> rs = service.search(new SearchRequest(
                "report", 50, null, SearchScope.ALL, MatchMode.FUZZY, sub));
        assertEquals(Collections.singletonList("report-sub.txt"), names(rs),
                "scoped literal search returns only subtree hits");
    }

    // ── tri-state truncation ───────────────────────────────────────────────

    @Test
    void shouldProbeTruncationWithLimitPlusOne() {
        SearchOutcome o = detailed("zz", 3, SearchScope.NAME, MatchMode.FUZZY);
        assertEquals(3, o.results.size());
        assertEquals(TriState.TRUE, o.hasMore, "more hits exist beyond the page");

        o = detailed("zz", 5, SearchScope.NAME, MatchMode.FUZZY);
        assertEquals(5, o.results.size());
        assertEquals(TriState.FALSE, o.hasMore, "exact fit: proven complete");

        o = detailed("zz", 6, SearchScope.NAME, MatchMode.FUZZY);
        assertEquals(TriState.FALSE, o.hasMore);
    }

    @Test
    void shouldReturnUnknownFromDefaultWrapper() {
        SearchOutcome o = new FakeSearchService()
                .searchDetailed(new SearchRequest("x", 10, null, SearchScope.ALL, MatchMode.FUZZY));
        assertEquals(TriState.UNKNOWN, o.hasMore,
                "default wrapper must never claim a verdict it cannot prove");
    }

    // ── input limits ───────────────────────────────────────────────────────

    @Test
    void shouldRejectTooManyWordsAndOverlongToken() {
        String seventeen = "a b c d e f g h i j k l m n o p q"; // 17 words > 16
        SearchException e = assertThrows(SearchException.class,
                () -> search(seventeen, 50, SearchScope.NAME));
        assertEquals(SearchException.Code.INVALID_QUERY, e.getCode());

        StringBuilder longTok = new StringBuilder();
        for (int i = 0; i < 65; i++) longTok.append('x'); // 65 > 64 code points
        e = assertThrows(SearchException.class,
                () -> search(longTok.toString(), 50, SearchScope.NAME));
        assertEquals(SearchException.Code.INVALID_QUERY, e.getCode());
    }
}
