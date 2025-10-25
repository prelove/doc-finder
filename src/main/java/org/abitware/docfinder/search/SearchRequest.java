package org.abitware.docfinder.search;

/** Request bundle for executing a search with optional filters. */
public class SearchRequest {
    public final String query;
    public final int limit;
    public final FilterState filter;

    public SearchRequest(String query, int limit, FilterState filter) {
        this.query = (query == null) ? "" : query;
        this.limit = Math.max(1, limit);
        this.filter = filter;
    }
}
