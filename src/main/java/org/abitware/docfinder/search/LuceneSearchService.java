package org.abitware.docfinder.search;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.analysis.ja.JapaneseAnalyzer;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import java.util.HashMap;
import java.util.Map;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.*;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.store.FSDirectory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 仅按 name 字段搜索（下一步再扩展 content） */
public class LuceneSearchService implements SearchService {
    private final Path indexDir;
    
    private final Analyzer queryAnalyzer;
    
    private volatile FilterState filter = new FilterState();

    public LuceneSearchService(Path indexDir) {
    	this.indexDir = indexDir;

        // 与索引侧一致的查询分析器：按字段路由
        Analyzer std = new StandardAnalyzer();
        Analyzer zh  = new SmartChineseAnalyzer();
        Analyzer ja  = new JapaneseAnalyzer();
        java.util.Map<String, Analyzer> map = new java.util.HashMap<>();
        map.put("name", std);
        map.put("content", std);
        map.put("content_zh", zh);
        map.put("content_ja", ja);

        this.queryAnalyzer = new PerFieldAnalyzerWrapper(std, map);
    }

    /**
     * 直接输入 kubernetes 也会命中文档内容；若想只搜内容，用 content:kubernetes；只搜文件名用 name:xxx。
     * 新增：支持文件名通配（name:*.xlsx / name:report-??.pdf），以及查询串就是通配（*.xlsx）。
     */
    @Override
    public List<SearchResult> search(String q, int limit) {
        List<SearchResult> out = new ArrayList<>();
        if (q == null || q.trim().isEmpty()) return out;

        try (DirectoryReader reader = DirectoryReader.open(FSDirectory.open(indexDir))) {
            IndexSearcher searcher = new IndexSearcher(reader);

            // ---- 0) 解析 name: 通配符，剥离出“剩余查询串” ----
            NamePreprocess np = extractNameWildcards(q);

            // ---- 1) 跨字段基础解析（name/content/...），允许前导通配 ----
            String[] fields = {"name", "content", "content_zh", "content_ja"};
            java.util.Map<String, Float> boosts = new java.util.HashMap<>();
            boosts.put("name", 2.0f);       // 文件名更高权重
            boosts.put("content", 1.2f);
            boosts.put("content_zh", 1.2f);
            boosts.put("content_ja", 1.2f);

            org.apache.lucene.queryparser.classic.MultiFieldQueryParser parser =
                    new org.apache.lucene.queryparser.classic.MultiFieldQueryParser(fields, queryAnalyzer, boosts);
            parser.setAllowLeadingWildcard(true); // 支持 *.xlsx 这种前导通配

            org.apache.lucene.search.Query userQuery = buildUserQuery(np.qRest, parser);

            // ---- 2) 根查询：用户查询（为空则 MatchAll）+ 文件名通配 MUST ----
            org.apache.lucene.search.BooleanQuery.Builder root = new org.apache.lucene.search.BooleanQuery.Builder();
            if (userQuery == null || userQuery.toString().isEmpty()
                    || (userQuery instanceof org.apache.lucene.search.BooleanQuery
                        && ((org.apache.lucene.search.BooleanQuery) userQuery).clauses().isEmpty())) {
                root.add(new org.apache.lucene.search.MatchAllDocsQuery(),
                        org.apache.lucene.search.BooleanClause.Occur.MUST);
            } else {
                root.add(userQuery, org.apache.lucene.search.BooleanClause.Occur.MUST);
            }

            // 将 name:*.xxx 片段转为 MUST 的 WildcardQuery 命中 name_raw，并对 *.ext 追加 ext 过滤以加速
            addNameWildcardMust(root, np.nameWildcards);

            // ---- 3) 应用你的过滤器（扩展名/时间范围） ----
            addFilters(root);

            org.apache.lucene.search.Query finalQuery = root.build();

            // ---- 4) 搜索 & 映射结果 ----
            int max = Math.max(1, Math.min(limit, 200));
            org.apache.lucene.search.TopDocs top = searcher.search(finalQuery, max);
            for (org.apache.lucene.search.ScoreDoc sd : top.scoreDocs) {
                org.apache.lucene.document.Document d = searcher.doc(sd.doc);

                long ctime = (d.getField("ctime") != null) ? d.getField("ctime").numericValue().longValue() : 0L;
                long atime = (d.getField("atime") != null) ? d.getField("atime").numericValue().longValue() : 0L;
                long size  = (d.getField("size")  != null) ? d.getField("size").numericValue().longValue()  : 0L;

                String match = detectMatchType(searcher, finalQuery, sd.doc);
                out.add(new SearchResult(d.get("name"), d.get("path"), sd.score, ctime, atime, match, size));
            }
        } catch (Exception ignore) { /* 可记录日志 */ }

        return out;
    }

    /* ==================== 以下为私有辅助方法（带注释，便于维护） ==================== */

    /** 结果承载：剩余查询串 + name: 通配片段列表 */
    private static class NamePreprocess {
        final String qRest;
        final java.util.List<String> nameWildcards;
        NamePreprocess(String qRest, java.util.List<String> pats) { this.qRest = qRest; this.nameWildcards = pats; }
    }

    /**
     * 抽取并剥离查询里的 name: 通配符片段；若整串就是通配（*.xlsx）且不含字段，也当作文件名通配。
     * 支持引号：name:"my report*.pdf"
     */
    private NamePreprocess extractNameWildcards(String qRaw) {
        String q = qRaw == null ? "" : qRaw.trim();
        java.util.regex.Pattern NAME_FILTER =
                java.util.regex.Pattern.compile("(?i)(?:^|\\s)name:(\"[^\"]+\"|'[^']+'|\\S+)");
        java.util.regex.Matcher m = NAME_FILTER.matcher(q);

        java.util.List<String> pats = new java.util.ArrayList<>();
        StringBuffer rest = new StringBuffer();
        while (m.find()) {
            String token = m.group(1);
            if ((token.startsWith("\"") && token.endsWith("\"")) || (token.startsWith("'") && token.endsWith("'"))) {
                token = token.substring(1, token.length() - 1);
            }
            pats.add(token);
            m.appendReplacement(rest, " "); // 从原串移除该片段
        }
        m.appendTail(rest);
        String qRest = rest.toString().trim();

        // 若没有 name:，但整串本身就是通配（且不含字段），也按文件名通配处理
        if (pats.isEmpty() && (q.contains("*") || q.contains("?")) && !q.contains(":")) {
            pats.add(q);
            qRest = "";
        }
        return new NamePreprocess(qRest, pats);
    }

    /**
     * 构造用户查询：跨字段解析（MUST）+ 文件名前缀加分（SHOULD）。
     * - 只有在“没有字段/空格/尾部通配”的短 token 时才做 name 前缀加分。
     */
    private org.apache.lucene.search.Query buildUserQuery(String qRest,
                                                         org.apache.lucene.queryparser.classic.MultiFieldQueryParser parser)
            throws org.apache.lucene.queryparser.classic.ParseException {
        if (qRest == null) qRest = "";
        org.apache.lucene.search.BooleanQuery.Builder b = new org.apache.lucene.search.BooleanQuery.Builder();

        if (!qRest.isEmpty()) {
            org.apache.lucene.search.Query parsed = parser.parse(qRest);
            b.add(parsed, org.apache.lucene.search.BooleanClause.Occur.MUST);

            boolean looksAsciiWord = qRest.matches("^[\\p{Alnum}_.\\-]+$");
            boolean hasField = qRest.contains(":");
            boolean hasSpace = qRest.contains(" ");
            boolean hasWildcard = qRest.endsWith("*");
            if (!hasField && !hasSpace && !hasWildcard && looksAsciiWord && qRest.length() >= 2) {
                org.apache.lucene.search.Query namePrefix =
                        new org.apache.lucene.search.PrefixQuery(
                                new org.apache.lucene.index.Term("name", qRest.toLowerCase(java.util.Locale.ROOT)));
                b.add(namePrefix, org.apache.lucene.search.BooleanClause.Occur.SHOULD);
            }
        }
        return b.build();
    }

    /**
     * 把 name: 通配符片段转成 MUST 的 WildcardQuery 命中 name_raw；
     * 若是 *.ext 形式，顺带追加 ext:ext 的 MUST 过滤以加速。
     */
    private void addNameWildcardMust(BooleanQuery.Builder root, List<String> patterns) {
        if (patterns == null || patterns.isEmpty()) return;

        for (String pat : patterns) {
            if (pat == null) continue;
            String p = pat.toLowerCase(java.util.Locale.ROOT).trim();
            if (p.isEmpty()) continue;

            boolean hasWildcard = (p.indexOf('*') >= 0) || (p.indexOf('?') >= 0);

            // *.ext → 附带 ext 过滤以加速
            int dot = p.lastIndexOf('.');
            if (p.startsWith("*") && dot >= 0 && dot < p.length()-1
                    && p.indexOf('*', dot) < 0 && p.indexOf('?', dot) < 0) {
                String ext = p.substring(dot+1);
                if (ext.matches("[\\p{Alnum}_-]+")) {
                    root.add(new TermQuery(new Term("ext", ext)), BooleanClause.Occur.MUST);
                }
            }

            // 主查询：name_raw
            Query qPrimary = hasWildcard
                    ? new WildcardQuery(new Term("name_raw", p))
                    : new TermQuery(new Term("name_raw", p));

            // 过渡兜底：name 短语匹配（老索引可能缺 name_raw）
            Query qFallback = null;
            if (!hasWildcard) {
                try {
                    QueryParser qp = new org.apache.lucene.queryparser.classic.QueryParser("name", queryAnalyzer);
                    qp.setAllowLeadingWildcard(true);
                    qFallback = qp.parse("\"" + p.replace("\"","\\\"") + "\""); // 尽量精确
                } catch (Exception ignore) {}
            }

            if (qFallback != null) {
                BooleanQuery.Builder either = new BooleanQuery.Builder();
                either.add(qPrimary, BooleanClause.Occur.SHOULD);
                either.add(qFallback, BooleanClause.Occur.SHOULD);
                root.add(either.build(), BooleanClause.Occur.MUST);
            } else {
                root.add(qPrimary, BooleanClause.Occur.MUST);
            }
        }
    }


    /** 应用扩展名与时间范围过滤（沿用你现有的 FilterState）。 */
    private void addFilters(org.apache.lucene.search.BooleanQuery.Builder root) {
        // 扩展名过滤
        if (filter != null && filter.exts != null && !filter.exts.isEmpty()) {
            org.apache.lucene.search.BooleanQuery.Builder e = new org.apache.lucene.search.BooleanQuery.Builder();
            for (String ex : filter.exts) {
                if (ex == null || ex.isEmpty()) continue;
                e.add(new org.apache.lucene.search.TermQuery(new org.apache.lucene.index.Term("ext", ex.toLowerCase(java.util.Locale.ROOT))),
                        org.apache.lucene.search.BooleanClause.Occur.SHOULD);
            }
            root.add(e.build(), org.apache.lucene.search.BooleanClause.Occur.MUST);
        }
        // 时间范围过滤（mtime_l 为 LongPoint）
        if (filter != null && (filter.fromEpochMs != null || filter.toEpochMs != null)) {
            long from = (filter.fromEpochMs == null) ? Long.MIN_VALUE : filter.fromEpochMs;
            long to   = (filter.toEpochMs   == null) ? Long.MAX_VALUE : filter.toEpochMs;
            root.add(org.apache.lucene.document.LongPoint.newRangeQuery("mtime_l", from, to),
                    org.apache.lucene.search.BooleanClause.Occur.MUST);
        }
    }

    
    private String detectMatchType(IndexSearcher searcher, org.apache.lucene.search.Query q, int docId) {
        try {
            org.apache.lucene.search.Explanation exp = searcher.explain(q, docId);
            boolean[] flags = new boolean[2]; // [0]=name, [1]=content
            walk(exp, flags);
            if (flags[0] && flags[1]) return "name+content";
            if (flags[0]) return "name";
            if (flags[1]) return "content";
        } catch (Exception ignore) {}
        return "";
    }
    
    private void walk(org.apache.lucene.search.Explanation e, boolean[] f) {
        String desc = e.getDescription().toLowerCase();
        // 粗匹配：描述里出现字段名
        if (desc.contains("name:")) f[0] = true;
        if (desc.contains("content:") || desc.contains("content_zh:") || desc.contains("content_ja:")) f[1] = true;
        for (org.apache.lucene.search.Explanation d : e.getDetails()) walk(d, f);
    }
    
    @Override public void setFilter(FilterState f) { this.filter = (f == null ? new FilterState() : f); }
}
