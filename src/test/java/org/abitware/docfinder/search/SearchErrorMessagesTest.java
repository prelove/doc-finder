package org.abitware.docfinder.search;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Headless coverage of the fixed user-facing text for all eight Codes plus
 * null/unknown fallback. Text must never embed exception messages or stacks.
 */
class SearchErrorMessagesTest {

    @Test
    void shouldReturnExactFixedTextForEveryCode() {
        assertEquals("Enter a valid absolute server directory path.",
                SearchErrorMessages.forCode(SearchException.Code.INVALID_DIRECTORY));
        assertEquals("This directory is outside configured sources. Add a source first.",
                SearchErrorMessages.forCode(SearchException.Code.DIRECTORY_NOT_INDEXED));
        assertEquals("Search sources could not be loaded. Check configuration and retry.",
                SearchErrorMessages.forCode(SearchException.Code.SOURCE_CONFIG_UNAVAILABLE));
        assertEquals("Check that sources are reachable, then run File > Index All Sources"
                        + " to update the index. If old entries remain, back up the index"
                        + " before using File > Rebuild Index (Full).",
                SearchErrorMessages.forCode(SearchException.Code.INDEX_REBUILD_REQUIRED));
        assertEquals("Directory search capability could not be checked. Retry later.",
                SearchErrorMessages.forCode(SearchException.Code.SCOPE_CHECK_FAILED));
        assertEquals("Search failed. See the application log.",
                SearchErrorMessages.forCode(SearchException.Code.SEARCH_FAILED));
        assertEquals("Invalid search query. Check the search syntax and retry.",
                SearchErrorMessages.forCode(SearchException.Code.INVALID_QUERY));
        assertEquals("Search index is unavailable. Run File > Index All Sources in the"
                        + " desktop app, or retry after initialization or rebuilding.",
                SearchErrorMessages.forCode(SearchException.Code.INDEX_UNAVAILABLE));
    }

    @Test
    void shouldFallBackToSearchFailedForNullCode() {
        assertEquals(SearchErrorMessages.forCode(SearchException.Code.SEARCH_FAILED),
                SearchErrorMessages.forCode(null));
    }

    @Test
    void shouldNeverExposeRawExceptionDetails() {
        String secret = "s3cret-token \"C:\\Users\\admin\\secrets.txt\"";
        SearchException ex = new SearchException(
                SearchException.Code.SEARCH_FAILED, secret, new RuntimeException("inner"));
        String shown = SearchErrorMessages.forCode(ex.getCode());
        assertFalse(shown.contains(secret));
        assertFalse(shown.contains("inner"));
        assertFalse(shown.contains("Exception"));
    }
}
