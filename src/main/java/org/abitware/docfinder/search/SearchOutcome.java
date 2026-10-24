package org.abitware.docfinder.search;

import java.util.Collections;
import java.util.List;

/**
 * Detailed search outcome: the page of results plus a tri-state verdict on
 * whether more results exist beyond the requested limit (D5/B-05).
 */
public class SearchOutcome {
    public final List<SearchResult> results;
    public final TriState hasMore;

    public SearchOutcome(List<SearchResult> results, TriState hasMore) {
        this.results = (results == null) ? Collections.<SearchResult>emptyList() : results;
        this.hasMore = (hasMore == null) ? TriState.UNKNOWN : hasMore;
    }
}
