package org.abitware.docfinder.search;

import java.util.List;

/** 搜索服务接口：稍后接 Lucene；现在用假数据实现 */
public interface SearchService {
    java.util.List<SearchResult> search(String q, int limit);
    default void setFilter(FilterState f) {} // 默认无效；Lucene 实现会覆盖
}