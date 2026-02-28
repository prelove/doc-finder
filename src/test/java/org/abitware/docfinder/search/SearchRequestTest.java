package org.abitware.docfinder.search;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SearchHistoryManagerTest {

    @TempDir
    Path tempDir;

    private SearchHistoryManager newManager() {
        // Override the default base dir by pointing at a temp location.
        // SearchHistoryManager reads the path from AppPaths, so we use a simple
        // file-based approach through the public API only.
        return new SearchHistoryManager();
    }

    @Test
    void shouldReturnEnums() {
        // Verify the enums exist and have the expected labels
        assertEquals("Name + Content", SearchScope.ALL.toString());
        assertEquals("Name Only", SearchScope.NAME.toString());
        assertEquals("Content Only", SearchScope.CONTENT.toString());
        assertEquals("Folders", SearchScope.FOLDER.toString());
    }

    @Test
    void shouldHaveMatchModeEnums() {
        assertEquals("Fuzzy", MatchMode.FUZZY.toString());
        assertEquals("Exact", MatchMode.EXACT.toString());
    }

    @Test
    void shouldBuildSearchRequest() {
        FilterState filter = new FilterState();
        filter.exts = FilterState.parseExts("pdf");

        SearchRequest req = new SearchRequest("hello", 50, filter, SearchScope.NAME, MatchMode.EXACT);
        assertEquals("hello", req.query);
        assertEquals(50, req.limit);
        assertEquals(SearchScope.NAME, req.scope);
        assertEquals(MatchMode.EXACT, req.matchMode);
        assertNotNull(req.filter);
    }

    @Test
    void shouldDefaultToAllAndFuzzy() {
        SearchRequest req = new SearchRequest("test", 10, null);
        assertEquals(SearchScope.ALL, req.scope);
        assertEquals(MatchMode.FUZZY, req.matchMode);
    }

    @Test
    void shouldNormalizeNullQuery() {
        SearchRequest req = new SearchRequest(null, 10, null);
        assertEquals("", req.query);
    }

    @Test
    void shouldEnforceMinimumLimit() {
        SearchRequest req = new SearchRequest("q", 0, null);
        assertEquals(1, req.limit);
    }
}
