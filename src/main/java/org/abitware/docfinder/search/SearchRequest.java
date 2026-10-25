package org.abitware.docfinder.search;

/** Request bundle for executing a search with optional filters and scope. */
public class SearchRequest {
    public final String query;
    public final int limit;
    public final FilterState filter;
    public final SearchScope scope;
    public final MatchMode matchMode;
    /** Optional directory restriction; trimmed snapshot, no I/O here. */
    public final String directoryPath;
    /**
     * Optional caller/session identity for cancellation scoping: a newer
     * request with the SAME tag supersedes in-flight work of that caller;
     * different tags never cancel each other. Null = private session that
     * is never superseded.
     */
    public final String clientTag;

    public SearchRequest(String query, int limit, FilterState filter) {
        this(query, limit, filter, SearchScope.ALL, MatchMode.FUZZY);
    }

    public SearchRequest(String query, int limit, FilterState filter, SearchScope scope, MatchMode matchMode) {
        this(query, limit, filter, scope, matchMode, null);
    }

    public SearchRequest(String query, int limit, FilterState filter, SearchScope scope,
                         MatchMode matchMode, String directoryPath) {
        this(query, limit, filter, scope, matchMode, directoryPath, null);
    }

    public SearchRequest(String query, int limit, FilterState filter, SearchScope scope,
                         MatchMode matchMode, String directoryPath, String clientTag) {
        this.query = (query == null) ? "" : query;
        this.limit = Math.max(1, limit);
        this.filter = filter;
        this.scope = (scope == null) ? SearchScope.ALL : scope;
        this.matchMode = (matchMode == null) ? MatchMode.FUZZY : matchMode;
        this.directoryPath = (directoryPath == null) ? null : directoryPath.trim();
        this.clientTag = (clientTag == null || clientTag.trim().isEmpty()) ? null : clientTag.trim();
    }
}
