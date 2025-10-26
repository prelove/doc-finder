package org.abitware.docfinder.search;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.analysis.ja.JapaneseAnalyzer;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Explanation;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.WildcardQuery;
import org.apache.lucene.store.FSDirectory;

/** 仅按 name 字段搜索（下一步再扩展 content） */
public class LuceneSearchService implements SearchService {
    private static final String FIELD_NAME = "name";
    private static final String FIELD_NAME_RAW = "name_raw";
    private static final String FIELD_PATH = "path";
    private static final String FIELD_EXT = "ext";
    private static final String FIELD_KIND = "kind";
    private static final String FIELD_MTIME_DISPLAY = "mtime";
    private static final String FIELD_ATIME_DISPLAY = "atime";
    private static final String FIELD_CTIME_DISPLAY = "ctime";
    private static final String FIELD_SIZE = "size";

    private static final String KIND_FOLDER = "folder";

    private static final String[] CONTENT_FIELDS = { "content", "content_zh", "content_ja" };

    private final Path indexDir;
    
    private final Analyzer queryAnalyzer;
    

    public LuceneSearchService(Path indexDir) {
    	this.indexDir = indexDir;

        Analyzer std = new StandardAnalyzer();
        Analyzer zh  = new SmartChineseAnalyzer();
        Analyzer ja  = new JapaneseAnalyzer();
        Map<String, Analyzer> perField = new HashMap<>();
        perField.put(FIELD_NAME, std);
        perField.put("content", std);
        perField.put("content_zh", zh);
        perField.put("content_ja", ja);

        this.queryAnalyzer = new PerFieldAnalyzerWrapper(std, perField);
    }

    @Override
    public List<SearchResult> search(SearchRequest request) {
        List<SearchResult> out = new ArrayList<>();
        if (request == null) return out;

        String rawQuery = request.query == null ? "" : request.query.trim();
        if (rawQuery.isEmpty()) return out;

        FilterState filter = request.filter;
        SearchScope scope = request.scope == null ? SearchScope.ALL : request.scope;
        MatchMode matchMode = request.matchMode == null ? MatchMode.FUZZY : request.matchMode;

        try (DirectoryReader reader = DirectoryReader.open(FSDirectory.open(indexDir))) {
            IndexSearcher searcher = new IndexSearcher(reader);

            NamePreprocess np = extractNameWildcards(rawQuery);
            boolean advanced = hasAdvancedSyntax(np.qRest);

            Query userQuery = buildUserQuery(np.qRest, scope, matchMode, advanced);

            BooleanQuery.Builder root = new BooleanQuery.Builder();
            if (userQuery == null || (userQuery instanceof BooleanQuery && ((BooleanQuery) userQuery).clauses().isEmpty())) {
                root.add(new MatchAllDocsQuery(), BooleanClause.Occur.MUST);
            } else {
                root.add(userQuery, BooleanClause.Occur.MUST);
            }

            addNameWildcardMust(root, np.nameWildcards);

            if (scope == SearchScope.FOLDER) {
                root.add(new TermQuery(new Term(FIELD_KIND, KIND_FOLDER)), BooleanClause.Occur.MUST);
            }

            addFilters(root, filter);

            Query finalQuery = root.build();

            int fetchLimit = Math.max(1, Math.min(request.limit, 200));
            TopDocs top = searcher.search(finalQuery, fetchLimit * 2);
            for (ScoreDoc sd : top.scoreDocs) {
                Document d = searcher.doc(sd.doc);

                boolean isFolder = KIND_FOLDER.equalsIgnoreCase(d.get(FIELD_KIND));
                if (scope != SearchScope.FOLDER && isFolder) {
                    continue;
                }

                String name = d.get(FIELD_NAME);
                String path = d.get(FIELD_PATH);
                long ctime = getStoredLong(d, FIELD_CTIME_DISPLAY);
                long atime = getStoredLong(d, FIELD_ATIME_DISPLAY);
                long size = getStoredLong(d, FIELD_SIZE);
                if (isFolder) size = 0L;

                String match = isFolder ? "folder" : detectMatchType(searcher, finalQuery, sd.doc);

                out.add(new SearchResult(name, path, sd.score, ctime, atime, match, size, isFolder));
                if (out.size() >= fetchLimit) {
                    break;
                }
            }
        } catch (Exception ignore) {
            // TODO: log exception details when logging framework available
        }

        return out;
    }

    private Query buildUserQuery(String qRest, SearchScope scope, MatchMode matchMode, boolean advanced) throws ParseException {
        if (qRest == null) qRest = "";
        if (qRest.isEmpty()) return null;

        if (matchMode == MatchMode.EXACT && !advanced) {
            return buildExactQuery(qRest, scope);
        }

        String[] fields = fieldsForScope(scope);
        Map<String, Float> boosts = new HashMap<>();
        for (String field : fields) {
            if (FIELD_NAME.equals(field)) {
                boosts.put(field, 2.0f);
            } else {
                boosts.put(field, 1.2f);
            }
        }

        MultiFieldQueryParser parser = new MultiFieldQueryParser(fields, queryAnalyzer, boosts);
        parser.setAllowLeadingWildcard(true);
        Query parsed = parser.parse(qRest);

        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        boolean prefixEligible = includesName(scope) && matchMode == MatchMode.FUZZY;
        boolean allowPrefix = false;
        if (prefixEligible) {
            allowPrefix = shouldAddNamePrefix(qRest);
            if (!allowPrefix && scope == SearchScope.FOLDER) {
                allowPrefix = allowFolderPrefix(qRest);
            }
        }

        if (allowPrefix) {
            String lower = qRest.toLowerCase(Locale.ROOT);
            Query namePrefixRaw = new PrefixQuery(new Term(FIELD_NAME_RAW, lower));
            Query namePrefixText = new PrefixQuery(new Term(FIELD_NAME, lower));
            BooleanQuery.Builder prefixEither = new BooleanQuery.Builder();
            prefixEither.add(namePrefixRaw, BooleanClause.Occur.SHOULD);
            prefixEither.add(namePrefixText, BooleanClause.Occur.SHOULD);
            if (scope == SearchScope.FOLDER) {
                Query containsRaw = new WildcardQuery(new Term(FIELD_NAME_RAW, "*" + lower + "*"));
                prefixEither.add(containsRaw, BooleanClause.Occur.SHOULD);
            }
            Query namePrefix = prefixEither.build();
            if (scope == SearchScope.NAME || scope == SearchScope.FOLDER) {
                BooleanQuery.Builder either = new BooleanQuery.Builder();
                either.add(parsed, BooleanClause.Occur.SHOULD);
                either.add(namePrefix, BooleanClause.Occur.SHOULD);
                builder.add(either.build(), BooleanClause.Occur.MUST);
            } else {
                builder.add(parsed, BooleanClause.Occur.MUST);
                builder.add(namePrefix, BooleanClause.Occur.SHOULD);
            }
        } else {
            builder.add(parsed, BooleanClause.Occur.MUST);
        }

        return builder.build();
    }

    private Query buildExactQuery(String query, SearchScope scope) throws ParseException {
        String escaped = QueryParser.escape(query);
        BooleanQuery.Builder builder = new BooleanQuery.Builder();

        if (includesName(scope)) {
            builder.add(new TermQuery(new Term(FIELD_NAME_RAW, query.toLowerCase(Locale.ROOT))), BooleanClause.Occur.SHOULD);
            QueryParser qp = new QueryParser(FIELD_NAME, queryAnalyzer);
            qp.setAllowLeadingWildcard(false);
            builder.add(qp.parse("\"" + escaped + "\""), BooleanClause.Occur.SHOULD);
        }

        if (includesContent(scope)) {
            BooleanQuery.Builder content = new BooleanQuery.Builder();
            for (String field : CONTENT_FIELDS) {
                QueryParser qp = new QueryParser(field, queryAnalyzer);
                qp.setAllowLeadingWildcard(false);
                content.add(qp.parse("\"" + escaped + "\""), BooleanClause.Occur.SHOULD);
            }
            builder.add(content.build(), BooleanClause.Occur.SHOULD);
        }

        BooleanQuery exact = builder.build();
        if (exact.clauses().isEmpty()) {
            return null;
        }
        return exact;
    }

    private boolean includesName(SearchScope scope) {
        return scope == SearchScope.ALL || scope == SearchScope.NAME || scope == SearchScope.FOLDER;
    }

    private boolean includesContent(SearchScope scope) {
        return scope == SearchScope.ALL || scope == SearchScope.CONTENT;
    }

    private String[] fieldsForScope(SearchScope scope) {
        switch (scope) {
            case NAME:
            case FOLDER:
                return new String[] { FIELD_NAME };
            case CONTENT:
                return CONTENT_FIELDS;
            case ALL:
            default:
                return new String[] { FIELD_NAME, CONTENT_FIELDS[0], CONTENT_FIELDS[1], CONTENT_FIELDS[2] };
        }
    }

    private boolean allowFolderPrefix(String qRest) {
        if (qRest == null || qRest.isEmpty()) return false;
        if (qRest.contains(":") || qRest.contains(" ") || qRest.endsWith("*")) return false;
        for (int i = 0; i < qRest.length(); i++) {
            char c = qRest.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == '-')) {
                return false;
            }
        }
        return true;
    }

    private boolean shouldAddNamePrefix(String qRest) {
        boolean looksAsciiWord = qRest.matches("^[\\p{Alnum}_.\\-]+$");
        boolean hasField = qRest.contains(":");
        boolean hasSpace = qRest.contains(" ");
        boolean hasWildcard = qRest.endsWith("*");
        return !hasField && !hasSpace && !hasWildcard && looksAsciiWord && qRest.length() >= 2;
    }

    private boolean hasAdvancedSyntax(String input) {
        if (input == null || input.isEmpty()) return false;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == ':' || c == '*' || c == '?' || c == '"' || c == '(' || c == ')' || c == '!') {
                return true;
            }
        }
        return false;
    }

    private void addNameWildcardMust(BooleanQuery.Builder root, List<String> patterns) {
        if (patterns == null || patterns.isEmpty()) return;

        for (String pat : patterns) {
            if (pat == null) continue;
            String p = pat.toLowerCase(Locale.ROOT).trim();
            if (p.isEmpty()) continue;

            boolean hasWildcard = (p.indexOf('*') >= 0) || (p.indexOf('?') >= 0);

            int dot = p.lastIndexOf('.');
            if (p.startsWith("*") && dot >= 0 && dot < p.length() - 1
                    && p.indexOf('*', dot) < 0 && p.indexOf('?', dot) < 0) {
                String ext = p.substring(dot + 1);
                if (ext.matches("[\\p{Alnum}_-]+")) {
                    root.add(new TermQuery(new Term(FIELD_EXT, ext)), BooleanClause.Occur.MUST);
                }
            }

            Query qPrimary = hasWildcard
                    ? new WildcardQuery(new Term(FIELD_NAME_RAW, p))
                    : new TermQuery(new Term(FIELD_NAME_RAW, p));

            Query qFallback = null;
            if (!hasWildcard) {
                try {
                    QueryParser qp = new QueryParser(FIELD_NAME, queryAnalyzer);
                    qp.setAllowLeadingWildcard(true);
                    qFallback = qp.parse("\"" + p.replace("\"", "\\\"") + "\"");
                } catch (Exception ignore) {
                }
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

    /** 应用扩展名与时间范围过滤（沿用你的 FilterState） */
    private void addFilters(BooleanQuery.Builder root, FilterState filter) {
        if (filter != null && filter.exts != null && !filter.exts.isEmpty()) {
            BooleanQuery.Builder e = new BooleanQuery.Builder();
            for (String ex : filter.exts) {
                if (ex == null || ex.isEmpty()) continue;
                e.add(new TermQuery(new Term(FIELD_EXT, ex.toLowerCase(Locale.ROOT))),
                        BooleanClause.Occur.SHOULD);
            }
            root.add(e.build(), BooleanClause.Occur.MUST);
        }
        if (filter != null && (filter.fromEpochMs != null || filter.toEpochMs != null)) {
            long from = (filter.fromEpochMs == null) ? Long.MIN_VALUE : filter.fromEpochMs;
            long to   = (filter.toEpochMs   == null) ? Long.MAX_VALUE : filter.toEpochMs;
            root.add(LongPoint.newRangeQuery("mtime_l", from, to), BooleanClause.Occur.MUST);
        }
    }

    private long getStoredLong(Document doc, String field) {
        StoredField stored = (StoredField) doc.getField(field);
        return stored == null ? 0L : stored.numericValue().longValue();
    }

    private String detectMatchType(IndexSearcher searcher, Query q, int docId) {
        try {
            Explanation exp = searcher.explain(q, docId);
            boolean[] flags = new boolean[2]; // [0]=name, [1]=content
            walk(exp, flags);
            if (flags[0] && flags[1]) return "name+content";
            if (flags[0]) return "name";
            if (flags[1]) return "content";
        } catch (Exception ignore) {
        }
        return "";
    }
    
    private void walk(Explanation e, boolean[] f) {
        String desc = e.getDescription().toLowerCase(Locale.ROOT);
        if (desc.contains("name:")) f[0] = true;
        if (desc.contains("content:") || desc.contains("content_zh:") || desc.contains("content_ja:")) f[1] = true;
        for (Explanation d : e.getDetails()) walk(d, f);
    }
    
    private static class NamePreprocess {
        final String qRest;
        final List<String> nameWildcards;
        NamePreprocess(String qRest, List<String> pats) { this.qRest = qRest; this.nameWildcards = pats; }
    }
    
    private NamePreprocess extractNameWildcards(String qRaw) {
        String q = qRaw == null ? "" : qRaw.trim();
        Pattern NAME_FILTER = Pattern.compile("(?i)(?:^|\\s)name:(\"[^\"]+\"|'[^']+'|\\S+)");
        Matcher m = NAME_FILTER.matcher(q);

        List<String> pats = new ArrayList<>();
        StringBuffer rest = new StringBuffer();
        while (m.find()) {
            String token = m.group(1);
            if ((token.startsWith("\"") && token.endsWith("\"")) || (token.startsWith("'") && token.endsWith("'"))) {
                token = token.substring(1, token.length() - 1);
            }
            pats.add(token);
            m.appendReplacement(rest, " ");
        }
        m.appendTail(rest);
        String qRest = rest.toString().trim();

        if (pats.isEmpty() && (q.contains("*") || q.contains("?")) && !q.contains(":")) {
            pats.add(q);
            qRest = "";
        }
        return new NamePreprocess(qRest, pats);
    }
}
