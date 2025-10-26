package org.abitware.docfinder.search.query;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.search.Explanation;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.abitware.docfinder.search.FilterState;
import org.abitware.docfinder.search.MatchMode;
import org.abitware.docfinder.search.SearchRequest;
import org.abitware.docfinder.search.SearchResult;
import org.abitware.docfinder.search.SearchScope;

/**
 * Query executor that handles the execution of Lucene queries and
 * conversion of results to SearchResult objects.
 * This class encapsulates all query execution logic, including index access,
 * result processing, and match type detection.
 * 
 * @author DocFinder Team
 * @version 1.0
 * @since 1.0
 */
public class QueryExecutor {
    
    /** Field names used in the index */
    private static final String FIELD_NAME = "name";
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
    
    /** Index directory path */
    private final Path indexDir;
    
    /**
     * Constructs a QueryExecutor with the specified index directory.
     * 
     * @param indexDir the path to the Lucene index directory
     */
    public QueryExecutor(Path indexDir) {
        this.indexDir = indexDir;
    }
    
    /**
     * Executes a search request and returns the results.
     * 
     * @param request the search request containing query and parameters
     * @param query the Lucene query to execute
     * @return list of search results
     */
    public List<SearchResult> executeSearch(SearchRequest request, Query query) {
        List<SearchResult> results = new ArrayList<>();
        if (request == null || query == null) {
            return results;
        }
        
        try (DirectoryReader reader = DirectoryReader.open(FSDirectory.open(indexDir))) {
            IndexSearcher searcher = new IndexSearcher(reader);
            
            // Apply filters if specified
            Query finalQuery = applyFilters(query, request.filter, request.scope);
            
            // Execute search with limit
            int fetchLimit = Math.max(1, Math.min(request.limit, 200));
            TopDocs top = searcher.search(finalQuery, fetchLimit * 2);
            
            // Convert Lucene documents to SearchResult objects
            for (ScoreDoc sd : top.scoreDocs) {
                Document doc = searcher.doc(sd.doc);
                // Detect match type before creating result
                String matchType = detectMatchType(searcher, finalQuery, sd.doc);
                SearchResult result = convertToSearchResult(doc, sd.score, request.scope, matchType);
                
                if (result != null) {
                    results.add(result);
                }
                
                // Respect the limit
                if (results.size() >= fetchLimit) {
                    break;
                }
            }
        } catch (IOException ex) {
            // Log error and return empty results
            // TODO: Add proper logging when logging framework is available
            return Collections.emptyList();
        }
        
        return results;
    }
    
    /**
     * Applies filters to the query.
     * 
     * @param query the original query
     * @param filter the filter state
     * @param scope the search scope
     * @return the filtered query
     */
    private Query applyFilters(Query query, FilterState filter, SearchScope scope) {
        org.apache.lucene.search.BooleanQuery.Builder builder = 
            new org.apache.lucene.search.BooleanQuery.Builder();
        builder.add(query, org.apache.lucene.search.BooleanClause.Occur.MUST);
        
        // Apply extension filter
        if (filter != null && filter.exts != null && !filter.exts.isEmpty()) {
            org.apache.lucene.search.BooleanQuery.Builder extBuilder = 
                new org.apache.lucene.search.BooleanQuery.Builder();
            for (String ext : filter.exts) {
                if (ext == null || ext.isEmpty()) continue;
                extBuilder.add(new org.apache.lucene.search.TermQuery(
                    new org.apache.lucene.index.Term(FIELD_EXT, ext.toLowerCase(java.util.Locale.ROOT))),
                    org.apache.lucene.search.BooleanClause.Occur.SHOULD);
            }
            builder.add(extBuilder.build(), org.apache.lucene.search.BooleanClause.Occur.MUST);
        }
        
        // Apply time range filter
        if (filter != null && (filter.fromEpochMs != null || filter.toEpochMs != null)) {
            long from = (filter.fromEpochMs == null) ? Long.MIN_VALUE : filter.fromEpochMs;
            long to = (filter.toEpochMs == null) ? Long.MAX_VALUE : filter.toEpochMs;
            builder.add(LongPoint.newRangeQuery("mtime_l", from, to), 
                      org.apache.lucene.search.BooleanClause.Occur.MUST);
        }
        
        return builder.build();
    }
    
    /**
     * Converts a Lucene document to a SearchResult object.
     * 
     * @param doc the Lucene document
     * @param score the document score
     * @param scope the search scope
     * @param matchType the match type (name, content, etc.)
     * @return the SearchResult object, or null if document should be skipped
     */
    private SearchResult convertToSearchResult(Document doc, float score, SearchScope scope, String matchType) {
        String name = doc.get(FIELD_NAME);
        String path = doc.get(FIELD_PATH);
        String kind = doc.get(FIELD_KIND);
        
        // Skip folders if not searching for folders
        boolean isFolder = KIND_FOLDER.equalsIgnoreCase(kind);
        if (scope != SearchScope.FOLDER && isFolder) {
            return null;
        }
        
        // Extract timestamps
        long ctime = getStoredLong(doc, FIELD_CTIME_DISPLAY);
        long atime = getStoredLong(doc, FIELD_ATIME_DISPLAY);
        long size = getStoredLong(doc, FIELD_SIZE);
        if (isFolder) size = 0L;
        
        return new SearchResult(name, path, score, ctime, atime, matchType, size, isFolder);
    }
    
    /**
     * Extracts a stored long value from a document.
     * 
     * @param doc the Lucene document
     * @param field the field name
     * @return the long value, or 0 if not found
     */
    private long getStoredLong(Document doc, String field) {
        StoredField stored = (StoredField) doc.getField(field);
        return stored == null ? 0L : stored.numericValue().longValue();
    }
    
    /**
     * Detects the match type for a document (name, content, or both).
     * 
     * @param searcher the index searcher
     * @param query the executed query
     * @param docId the document ID
     * @return the match type string
     */
    private String detectMatchType(IndexSearcher searcher, Query query, int docId) {
        try {
            Explanation exp = searcher.explain(query, docId);
            boolean[] flags = new boolean[2]; // [0]=name, [1]=content
            walkExplanation(exp, flags);
            
            if (flags[0] && flags[1]) return "name+content";
            if (flags[0]) return "name";
            if (flags[1]) return "content";
        } catch (Exception ex) {
            // Ignore explanation errors
        }
        return "";
    }
    
    /**
     * Recursively walks through explanation to detect field matches.
     * 
     * @param exp the explanation to walk
     * @param flags array to set match flags
     */
    private void walkExplanation(Explanation exp, boolean[] flags) {
        String desc = exp.getDescription().toLowerCase(java.util.Locale.ROOT);
        if (desc.contains("name:")) flags[0] = true;
        if (desc.contains("content:") || desc.contains("content_zh:") || desc.contains("content_ja:")) {
            flags[1] = true;
        }
        
        for (Explanation child : exp.getDetails()) {
            walkExplanation(child, flags);
        }
    }
}