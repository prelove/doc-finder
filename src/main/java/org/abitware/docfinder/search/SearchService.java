package org.abitware.docfinder.search;

import java.util.List;

/** 搜索服务接口：接 Lucene 或其他实现 */
public interface SearchService {
    default List<SearchResult> search(String q, int limit) {
        return search(new SearchRequest(q, limit, null, SearchScope.ALL, MatchMode.FUZZY));
    }

    List<SearchResult> search(SearchRequest request);

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
