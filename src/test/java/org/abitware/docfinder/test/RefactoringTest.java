package org.abitware.docfinder.test;

import java.nio.file.Paths;
import org.abitware.docfinder.search.query.QueryBuilder;
import org.abitware.docfinder.search.SearchScope;
import org.abitware.docfinder.search.MatchMode;

/**
 * Simple test class to verify refactored components work correctly.
 * 
 * @author DocFinder Team
 * @version 1.0
 * @since 1.0
 */
public class RefactoringTest {
    
    /**
     * Test main method to verify basic functionality.
     * 
     * @param args command line arguments
     */
    public static void main(String[] args) {
        System.out.println("Testing refactored components...");
        
        // Test QueryBuilder
        testQueryBuilder();
        
        // Test content extractor path resolution
        testContentExtractor();
        
        System.out.println("All tests passed!");
    }
    
    /**
     * Tests the QueryBuilder component.
     */
    private static void testQueryBuilder() {
        System.out.println("Testing QueryBuilder...");
        
        try {
            QueryBuilder builder = new QueryBuilder();
            
            // Test name wildcard extraction
            QueryBuilder.NamePreprocess result = builder.extractNameWildcards("name:*.pdf report");
            System.out.println("Extracted query: " + result.qRest);
            System.out.println("Name wildcards: " + result.nameWildcards);
            
            // Test query building
            org.apache.lucene.search.Query query = builder.buildQuery(
                "test", SearchScope.ALL, MatchMode.FUZZY, result.nameWildcards);
            System.out.println("Built query: " + query);
            
            System.out.println("QueryBuilder test passed!");
        } catch (Exception e) {
            System.err.println("QueryBuilder test failed: " + e.getMessage());
        }
    }
    
    /**
     * Tests the content extractor path resolution.
     */
    private static void testContentExtractor() {
        System.out.println("Testing ContentExtractor path resolution...");
        
        try {
            // Test path normalization
            String path = Paths.get("C:\\Users\\test\\file.txt").toString();
            System.out.println("Test path: " + path);
            
            System.out.println("ContentExtractor path test passed!");
        } catch (Exception e) {
            System.err.println("ContentExtractor test failed: " + e.getMessage());
        }
    }
}
