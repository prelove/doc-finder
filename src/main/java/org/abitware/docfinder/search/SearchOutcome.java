package org.abitware.docfinder.search;

import java.util.Collections;
import java.util.List;

/**
 * Detailed search outcome: the page of results plus a tri-state verdict on
 * whether more results exist beyond the requested limit (D5/B-05).
 * {@code hasUnverified} marks outcomes where some hits could not be
 * existence-checked (offline source, permission/timeout indeterminate) —
 * those entries are kept but the caller should say they were not confirmed.
 */
public class SearchOutcome {
    public final List<SearchResult> results;
    public final TriState hasMore;
    public final boolean hasUnverified;

    public SearchOutcome(List<SearchResult> results, TriState hasMore) {
        this(results, hasMore, false);
    }

    public SearchOutcome(List<SearchResult> results, TriState hasMore, boolean hasUnverified) {
        this.results = (results == null) ? Collections.<SearchResult>emptyList() : results;
        this.hasMore = (hasMore == null) ? TriState.UNKNOWN : hasMore;
        this.hasUnverified = hasUnverified;
    }
}
