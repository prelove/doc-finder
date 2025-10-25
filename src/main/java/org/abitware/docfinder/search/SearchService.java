package org.abitware.docfinder.search;

import java.util.List;

public interface SearchService {
    default List<SearchResult> search(String q, int limit) {
        return search(new SearchRequest(q, limit, null));
    }

    List<SearchResult> search(SearchRequest request);
}
