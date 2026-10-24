package org.abitware.docfinder.search;

import java.util.List;

/** 搜索服务接口：接 Lucene 或其他实现 */
public interface SearchService {
    default List<SearchResult> search(String q, int limit) {
        return search(new SearchRequest(q, limit, null, SearchScope.ALL, MatchMode.FUZZY));
    }

    List<SearchResult> search(SearchRequest request);

    /**
     * Detailed search with a tri-state truncation verdict (D5/B-05).
     * Default wrapper returns {@link TriState#UNKNOWN}: a result list
     * shorter than the limit does not prove "no more results", and
     * UNKNOWN must never be presented as FALSE/complete. Implementations
     * that can prove truncation (e.g. limit+1 probing) should override.
     * One-direction delegation only — implementations must not make
     * search() and searchDetailed() call each other (R1-04).
     */
    default SearchOutcome searchDetailed(SearchRequest request) {
        return new SearchOutcome(search(request), TriState.UNKNOWN);
    }

    /**
     * Called after an index write is committed so that implementations can
     * proactively refresh their internal reader and stay near-real-time.
     * Default implementation is a no-op.
     */
    default void notifyIndexCommit() { }

    /**
     * Releases any resources held by this service, such as the SearcherManager.
     */
    default void close() {
        // Default implementation does nothing.
    }
}
