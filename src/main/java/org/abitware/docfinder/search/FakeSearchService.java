package org.abitware.docfinder.search;

import java.util.ArrayList;
import java.util.List;

/** 假数据实现：用于打磨 UI 交互节奏 */
public class FakeSearchService implements SearchService {
    @Override
    public List<SearchResult> search(SearchRequest request) {
        String q = (request == null || request.query == null) ? "" : request.query;
        int limit = (request == null) ? 20 : request.limit;

        List<SearchResult> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (int i = 1; i <= Math.max(5, Math.min(limit, 20)); i++) {
            out.add(new SearchResult(
                String.format("Sample-%02d-%s.txt", i, q.replaceAll("\\s+", "_")),
                String.format("C:/sample/path/%s/Sample-%02d.txt", q, i),
                1.0f / i,
                now - i * 86_400_000L,
                now - i * 43_200_000L,
                "name"
            ));
        }
        return out;
    }
}
