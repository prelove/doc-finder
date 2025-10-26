package org.abitware.docfinder.search;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.abitware.docfinder.search.query.QueryBuilder;
import org.abitware.docfinder.search.query.QueryExecutor;

/**
 * Lucene-based implementation of SearchService.
 * This class has been refactored to separate query building and execution
 * concerns into dedicated components.
 * 
 * @author DocFinder Team
 * @version 1.0
 * @since 1.0
 */
public class LuceneSearchServiceRefactored implements SearchService {

    /** Path to the Lucene index directory */
    private final Path indexDir;
    
    /** Component for building Lucene queries */
    private final QueryBuilder queryBuilder;
    
    /** Component for executing queries and processing results */
    private final QueryExecutor queryExecutor;
    
    /**
     * Constructs a LuceneSearchService with the specified index directory.
     * 
     * @param indexDir the path to the Lucene index directory
     */
    public LuceneSearchServiceRefactored(Path indexDir) {
        this.indexDir = indexDir;
        this.queryBuilder = new QueryBuilder();
        this.queryExecutor = new QueryExecutor(indexDir);
    }
    
    /**
     * {@inheritDoc}
     * 
     * This implementation delegates query building to QueryBuilder and
     * query execution to QueryExecutor.
     */
    @Override
    public List<SearchResult> search(SearchRequest request) {
        List<SearchResult> results = new ArrayList<>();
        if (request == null) {
            return results;
        }
        
        String rawQuery = request.query == null ? "" : request.query.trim();
        if (rawQuery.isEmpty()) {
            return results;
        }
        
        try {
            // Extract name wildcards from the query
            QueryBuilder.NamePreprocess np = queryBuilder.extractNameWildcards(rawQuery);
            
            // Build the Lucene query
            org.apache.lucene.search.Query luceneQuery = queryBuilder.buildQuery(
                rawQuery, request.scope, request.matchMode, np.nameWildcards);

            if (luceneQuery != null) {
                // Execute the query and return results
                results = queryExecutor.executeSearch(request, luceneQuery);
            }
            
        } catch (Exception ex) {
            // Log error and return empty results
            return results;
        }
        
        return results;
    }
    
    /**
     * Gets the path to the index directory.
     * 
     * @return the index directory path
     */
    public Path getIndexDir() {
        return indexDir;
    }
}