
package org.abitware.docfinder.search.query;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.abitware.docfinder.search.MatchMode;
import org.abitware.docfinder.search.SearchScope;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.analysis.ja.JapaneseAnalyzer;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.WildcardQuery;
import org.apache.lucene.index.Term;

/**
 * Builder class for constructing Lucene queries from search requests.
 * This class encapsulates all query building logic, including field-specific
 * queries, wildcard handling, and multi-language support.
 * 
 * @author DocFinder Team
 * @version 1.0
 * @since 1.0
 */
public class QueryBuilder {
    
    /** Field names used in the index */
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
    
    /** Content fields for different languages */
    private static final String[] CONTENT_FIELDS = { "content", "content_zh", "content_ja" };
    
    /** Analyzer for query parsing */
    private final Analyzer queryAnalyzer;
    
    /**
     * Constructs a QueryBuilder with the appropriate analyzers.
     */
    public QueryBuilder() {
        Analyzer std = new StandardAnalyzer();
        Analyzer zh = new SmartChineseAnalyzer();
        Analyzer ja = new JapaneseAnalyzer();
        
        Map<String, Analyzer> perField = new HashMap<>();
        perField.put(FIELD_NAME, std);
        perField.put("content", std);
        perField.put("content_zh", zh);
        perField.put("content_ja", ja);
        
        this.queryAnalyzer = new PerFieldAnalyzerWrapper(std, perField);
    }
    
    /**
     * Builds a complete Lucene query from the given search parameters.
     * 
     * @param query the raw query string
     * @param scope the search scope
     * @param matchMode the match mode
     * @param nameWildcards list of name wildcard patterns
     * @return the constructed Lucene query
     * @throws ParseException if query parsing fails
     */
    public Query buildQuery(String query, SearchScope scope, MatchMode matchMode,
                            List<String> nameWildcards) throws ParseException {
        
        // Extract name wildcards from the query
        NamePreprocess np = extractNameWildcards(query);
        boolean advanced = hasAdvancedSyntax(np.qRest);
        
        // Build the main user query
        Query userQuery = buildUserQuery(np.qRest, scope, matchMode, advanced);
        
        // Combine all parts into the final query
        BooleanQuery.Builder root = new BooleanQuery.Builder();
        
        // Add the main query or match all if empty
        if (userQuery == null || (userQuery instanceof BooleanQuery && ((BooleanQuery) userQuery).clauses().isEmpty())) {
            root.add(new org.apache.lucene.search.MatchAllDocsQuery(), BooleanClause.Occur.MUST);
        } else {
            root.add(userQuery, BooleanClause.Occur.MUST);
        }
        
        // Add name wildcard constraints
        addNameWildcardMust(root, nameWildcards);
        
        // Add folder constraint if searching only folders
        if (scope == SearchScope.FOLDER) {
            root.add(new TermQuery(new Term(FIELD_KIND, KIND_FOLDER)), BooleanClause.Occur.MUST);
        }
        
        return root.build();
    }
    
    /**
     * Builds the main user query based on the remaining query string after
     * extracting name wildcards.
     * 
     * @param qRest the remaining query string
     * @param scope the search scope
     * @param matchMode the match mode
     * @param advanced whether the query contains advanced syntax
     * @return the constructed user query
     * @throws ParseException if query parsing fails
     */
    private Query buildUserQuery(String qRest, SearchScope scope, MatchMode matchMode, boolean advanced) 
            throws ParseException {
        
        if (qRest == null) qRest = "";
        if (qRest.isEmpty()) return null;
        
        // Handle exact match mode
        if (matchMode == MatchMode.EXACT && !advanced) {
            return buildExactQuery(qRest, scope);
        }
        
        // Get fields and boosts for the scope
        String[] fields = fieldsForScope(scope);
        Map<String, Float> boosts = createFieldBoosts(fields);
        
        // Parse the query
        MultiFieldQueryParser parser = new MultiFieldQueryParser(fields, queryAnalyzer, boosts);
        parser.setAllowLeadingWildcard(true);
        Query parsed = parser.parse(qRest);
        
        // Build the final query with optional prefix boosting
        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        builder.add(parsed, BooleanClause.Occur.MUST);
        
        // Add prefix query if applicable
        if (shouldAddNamePrefix(qRest, scope, matchMode)) {
            Query namePrefix = buildNamePrefixQuery(qRest, scope);
            if (scope == SearchScope.NAME || scope == SearchScope.FOLDER) {
                // For name-only searches, make prefix an alternative
                BooleanQuery.Builder either = new BooleanQuery.Builder();
                either.add(parsed, BooleanClause.Occur.SHOULD);
                either.add(namePrefix, BooleanClause.Occur.SHOULD);
                builder.add(either.build(), BooleanClause.Occur.MUST);
            } else {
                // For mixed searches, add prefix as a boost
                builder.add(namePrefix, BooleanClause.Occur.SHOULD);
            }
        }
        
        return builder.build();
    }
    
    /**
     * Builds an exact match query.
     * 
     * @param query the exact query string
     * @param scope the search scope
     * @return the exact match query
     * @throws ParseException if query parsing fails
     */
    private Query buildExactQuery(String query, SearchScope scope) throws ParseException {
        String escaped = QueryParser.escape(query);
        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        
        // Add name field queries
        if (includesName(scope)) {
            // Raw name exact match
            builder.add(new TermQuery(new Term(FIELD_NAME_RAW, query.toLowerCase(Locale.ROOT))), 
                       BooleanClause.Occur.SHOULD);
            
            // Analyzed name exact match
            QueryParser nameParser = new QueryParser(FIELD_NAME, queryAnalyzer);
            nameParser.setAllowLeadingWildcard(false);
            builder.add(nameParser.parse("\"" + escaped + "\""), BooleanClause.Occur.SHOULD);
        }
        
        // Add content field queries
        if (includesContent(scope)) {
            BooleanQuery.Builder content = new BooleanQuery.Builder();
            for (String field : CONTENT_FIELDS) {
                QueryParser contentParser = new QueryParser(field, queryAnalyzer);
                contentParser.setAllowLeadingWildcard(false);
                content.add(contentParser.parse("\"" + escaped + "\""), BooleanClause.Occur.SHOULD);
            }
            builder.add(content.build(), BooleanClause.Occur.SHOULD);
        }
        
        BooleanQuery exact = builder.build();
        return exact.clauses().isEmpty() ? null : exact;
    }
    
    /**
     * Builds a name prefix query for boosting.
     * 
     * @param query the query string
     * @param scope the search scope
     * @return the prefix query
     */
    private Query buildNamePrefixQuery(String query, SearchScope scope) {
        String lower = query.toLowerCase(Locale.ROOT);
        
        // Build prefix query for both raw and analyzed name fields
        Query namePrefixRaw = new PrefixQuery(new Term(FIELD_NAME_RAW, lower));
        Query namePrefixText = new PrefixQuery(new Term(FIELD_NAME, lower));
        
        BooleanQuery.Builder prefixEither = new BooleanQuery.Builder();
        prefixEither.add(namePrefixRaw, BooleanClause.Occur.SHOULD);
        prefixEither.add(namePrefixText, BooleanClause.Occur.SHOULD);
        
        // For folder searches, also add contains query
        if (scope == SearchScope.FOLDER) {
            Query containsRaw = new WildcardQuery(new Term(FIELD_NAME_RAW, "*" + lower + "*"));
            prefixEither.add(containsRaw, BooleanClause.Occur.SHOULD);
        }
        
        return prefixEither.build();
    }
    
    /**
     * Adds name wildcard constraints to the query.
     * 
     * @param root the root query builder
     * @param patterns list of wildcard patterns
     */
    private void addNameWildcardMust(BooleanQuery.Builder root, List<String> patterns) {
        if (patterns == null || patterns.isEmpty()) return;
        
        for (String pat : patterns) {
            if (pat == null) continue;
            String p = pat.toLowerCase(Locale.ROOT).trim();
            if (p.isEmpty()) continue;
            
            boolean hasWildcard = (p.indexOf('*') >= 0) || (p.indexOf('?') >= 0);
            
            // Add extension filter for *.ext patterns
            addExtensionFilter(root, p);
            
            // Build the primary name query
            Query qPrimary = hasWildcard
                    ? new WildcardQuery(new Term(FIELD_NAME_RAW, p))
                    : new TermQuery(new Term(FIELD_NAME_RAW, p));
            
            // Build fallback analyzed query if no wildcards
            Query qFallback = null;
            if (!hasWildcard) {
                try {
                    QueryParser qp = new QueryParser(FIELD_NAME, queryAnalyzer);
                    qp.setAllowLeadingWildcard(true);
                    qFallback = qp.parse("\"" + p.replace("\"", "\\\"" ) + "\"");
                } catch (Exception ignore) {
                    // Ignore parsing errors
                }
            }
            
            // Combine primary and fallback queries
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
    
    /**
     * Adds extension filter for *.ext patterns.
     * 
     * @param root the root query builder
     * @param pattern the pattern to check for extension filter
     */
    private void addExtensionFilter(BooleanQuery.Builder root, String pattern) {
        int dot = pattern.lastIndexOf('.');
        if (pattern.startsWith("*") && dot >= 0 && dot < pattern.length() - 1
                && pattern.indexOf('*', dot) < 0 && pattern.indexOf('?', dot) < 0) {
            String ext = pattern.substring(dot + 1);
            if (ext.matches("[\\p{Alnum}_-]+")) {
                root.add(new TermQuery(new Term(FIELD_EXT, ext)), BooleanClause.Occur.MUST);
            }
        }
    }
    
    /**
     * Determines if a name prefix query should be added.
     * 
     * @param qRest the query string
     * @param scope the search scope
     * @param matchMode the match mode
     * @return true if prefix query should be added
     */
    private boolean shouldAddNamePrefix(String qRest, SearchScope scope, MatchMode matchMode) {
        boolean prefixEligible = includesName(scope) && matchMode == MatchMode.FUZZY;
        if (!prefixEligible) return false;
        
        // Check basic ASCII word pattern
        boolean looksAsciiWord = qRest.matches("^[\\p{Alnum}_.\\-]+$");
        boolean hasField = qRest.contains(":");
        boolean hasSpace = qRest.contains(" ");
        boolean hasWildcard = qRest.endsWith("*");
        
        if (hasField || hasSpace || hasWildcard || !looksAsciiWord || qRest.length() < 2) {
            return false;
        }
        
        // Special handling for folder searches
        if (scope == SearchScope.FOLDER) {
            return allowFolderPrefix(qRest);
        }
        
        return true;
    }
    
    /**
     * Checks if folder prefix is allowed for the query.
     * 
     * @param qRest the query string
     * @return true if folder prefix is allowed
     */
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
    
    /**
     * Checks if the query contains advanced syntax.
     * 
     * @param input the query string
     * @return true if advanced syntax is detected
     */
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
    
    /**
     * Gets the fields to search based on scope.
     * 
     * @param scope the search scope
     * @return array of field names
     */
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
    
    /**
     * Creates field boosts for the given fields.
     * 
     * @param fields the fields to boost
     * @return map of field names to boost values
     */
    private Map<String, Float> createFieldBoosts(String[] fields) {
        Map<String, Float> boosts = new HashMap<>();
        for (String field : fields) {
            if (FIELD_NAME.equals(field)) {
                boosts.put(field, 2.0f);
            } else {
                boosts.put(field, 1.2f);
            }
        }
        return boosts;
    }
    
    /**
     * Checks if name field should be included in search.
     * 
     * @param scope the search scope
     * @return true if name field should be included
     */
    private boolean includesName(SearchScope scope) {
        return scope == SearchScope.ALL || scope == SearchScope.NAME || scope == SearchScope.FOLDER;
    }
    
    /**
     * Checks if content fields should be included in search.
     * 
     * @param scope the search scope
     * @return true if content fields should be included
     */
    private boolean includesContent(SearchScope scope) {
        return scope == SearchScope.ALL || scope == SearchScope.CONTENT;
    }
    
    /**
     * Extracts name wildcard patterns from the query.
     * This method is public to allow external access from LuceneSearchService.
     * 
     * @param qRaw the raw query string
     * @return preprocessing result with extracted patterns
     */
    public NamePreprocess extractNameWildcards(String qRaw) {
        String q = qRaw == null ? "" : qRaw.trim();
        Pattern nameFilter = Pattern.compile("(?i)(?:^|\\s)name:(\"[^\"]+\"|'[^']+'|\\S+)");
        Matcher m = nameFilter.matcher(q);

        List<String> pats = new ArrayList<>();
        StringBuffer rest = new StringBuffer();
        
        while (m.find()) {
            String token = m.group(1);
            // Remove quotes if present
            if ((token.startsWith("\"") && token.endsWith("\"")) ||
                (token.startsWith("'") && token.endsWith("'"))) {
                token = token.substring(1, token.length() - 1);
            }
            pats.add(token);
            m.appendReplacement(rest, " ");
        }
        m.appendTail(rest);
        String qRest = rest.toString().trim();
        
        // If no explicit name: patterns but query has wildcards, treat whole query as name wildcard
        if (pats.isEmpty() && (q.contains("*") || q.contains("?")) && !q.contains(":")) {
            pats.add(q);
            qRest = "";
        }
        
        return new NamePreprocess(qRest, pats);
    }
    
    /**
     * Container for preprocessing results.
     */
    public static class NamePreprocess {
        public final String qRest;
        public final List<String> nameWildcards;
        NamePreprocess(String qRest, List<String> pats) {
            this.qRest = qRest; 
            this.nameWildcards = pats; 
        }
    }
}
