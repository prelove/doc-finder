package org.abitware.docfinder.search;

/** Request bundle for executing a search with optional filters and scope. */
public class SearchRequest {
    public final String query;
    public final int limit;
    public final FilterState filter;
    public final SearchScope scope;
    public final MatchMode matchMode;

    public SearchRequest(String query, int limit, FilterState filter) {
        this(query, limit, filter, SearchScope.ALL, MatchMode.FUZZY);
    }

    public SearchRequest(String query, int limit, FilterState filter, SearchScope scope, MatchMode matchMode) {
        this.query = (query == null) ? "" : query;
        this.limit = Math.max(1, limit);
        this.filter = filter;
        this.scope = (scope == null) ? SearchScope.ALL : scope;
        this.matchMode = (matchMode == null) ? MatchMode.FUZZY : matchMode;
    }
}
