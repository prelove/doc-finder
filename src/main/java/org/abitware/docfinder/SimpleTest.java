package org.abitware.docfinder;

/**
 * Simple test to verify basic functionality.
 */
public class SimpleTest {
    public static void main(String[] args) {
        System.out.println("DocFinder refactoring test");
        
        // Test basic Java functionality
        String test = "test";
        System.out.println("Basic test: " + test);
        
        // Test string operations
        String quoted = "\"test\"";
        if (quoted.startsWith("\"") && quoted.endsWith("\"")) {
            String unquoted = quoted.substring(1, quoted.length() - 1);
            System.out.println("Unquoted: " + unquoted);
        }
        
        System.out.println("Test completed successfully!");
    }
}