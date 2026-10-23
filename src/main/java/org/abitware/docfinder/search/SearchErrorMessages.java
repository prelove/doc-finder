package org.abitware.docfinder.search;

/**
 * Fixed user-facing English text for each {@link SearchException.Code},
 * shared by the desktop status bar and the HTTP layer (design D3). Never
 * includes the original exception message or stack trace.
 */
public final class SearchErrorMessages {

    private SearchErrorMessages() {
    }

    public static String forCode(SearchException.Code code) {
        if (code == null) {
            return forCode(SearchException.Code.SEARCH_FAILED);
        }
        switch (code) {
            case INVALID_DIRECTORY:
                return "Enter a valid absolute server directory path.";
            case DIRECTORY_NOT_INDEXED:
                return "This directory is outside configured sources. Add a source first.";
            case SOURCE_CONFIG_UNAVAILABLE:
                return "Search sources could not be loaded. Check configuration and retry.";
            case INDEX_REBUILD_REQUIRED:
                return "Check that sources are reachable, then run File > Index All Sources"
                        + " to update the index. If old entries remain, back up the index"
                        + " before using File > Rebuild Index (Full).";
            case SCOPE_CHECK_FAILED:
                return "Directory search capability could not be checked. Retry later.";
            case SEARCH_FAILED:
                return "Search failed. See the application log.";
            case INVALID_QUERY:
                return "Invalid search query. Check the search syntax and retry.";
            case INDEX_UNAVAILABLE:
                return "Search index is unavailable. Run File > Index All Sources in the"
                        + " desktop app, or retry after initialization or rebuilding.";
            default:
                return forCode(SearchException.Code.SEARCH_FAILED);
        }
    }
}
