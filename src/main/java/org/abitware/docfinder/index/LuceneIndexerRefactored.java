package org.abitware.docfinder.index;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.abitware.docfinder.index.content.DocumentBuilder;
import org.abitware.docfinder.util.Utils;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.analysis.ja.JapaneseAnalyzer;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

/**
 * Refactored Lucene indexer that uses separated components for content extraction
 * and document building. This class focuses on the core indexing operations
 * while delegating content extraction and document creation to specialized components.
 * 
 * @author DocFinder Team
 * @version 1.0
 * @since 1.0
 */
public class LuceneIndexerRefactored {
    
    /** Index directory path */
    private final Path indexDir;
    
    /** Index settings */
    private final IndexSettings settings;
    
    /** Analyzer for indexing */
    private final Analyzer analyzer;
    
    /** Document builder for creating Lucene documents */
    private final DocumentBuilder documentBuilder;
    
    /**
     * Constructs a LuceneIndexerRefactored with the specified index directory and settings.
     * 
     * @param indexDir the path to the index directory
     * @param settings the index settings
     */
    public LuceneIndexerRefactored(Path indexDir, IndexSettings settings) {
        this.indexDir = indexDir;
        this.settings = settings;
        this.analyzer = createAnalyzer();
        this.documentBuilder = new DocumentBuilder(settings);
    }
    
    /**
     * Creates or updates a document for the specified file.
     * If the file doesn't exist, it will be deleted from the index.
     * 
     * @param file the file to index
     * @throws IOException if an I/O error occurs
     */
    public void upsertFile(Path file) throws IOException {
        if (file == null) {
            return;
        }
        
        // Ensure index directory exists
        Files.createDirectories(indexDir);
        
        try (Directory dir = FSDirectory.open(indexDir)) {
            IndexWriterConfig config = new IndexWriterConfig(analyzer)
                    .setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
            
            try (IndexWriter writer = new IndexWriter(dir, config)) {
                if (!Files.exists(file)) {
                    // File doesn't exist, delete from index
                    deleteDocument(writer, file);
                } else if (Files.isDirectory(file)) {
                    // Index directory
                    indexDirectory(writer, file);
                } else {
                    // Index file
                    indexFile(writer, file);
                }
                
                writer.commit();
            }
        }
    }
    
    /**
     * Deletes a document from the index.
     * 
     * @param file the file to delete from index
     * @throws IOException if an I/O error occurs
     */
    public void deletePath(Path file) throws IOException {
        if (file == null) {
            return;
        }
        
        Files.createDirectories(indexDir);
        
        try (Directory dir = FSDirectory.open(indexDir)) {
            IndexWriterConfig config = new IndexWriterConfig(analyzer)
                    .setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
            
            try (IndexWriter writer = new IndexWriter(dir, config)) {
                deleteDocument(writer, file);
                writer.commit();
            }
        }
    }
    
    /**
     * Indexes a single directory.
     * 
     * @param writer the index writer
     * @param dir the directory to index
     * @throws IOException if an I/O error occurs
     */
    private void indexDirectory(IndexWriter writer, Path dir) throws IOException {
        BasicFileAttributes attrs = Files.readAttributes(dir, BasicFileAttributes.class);
        
        // Create and add directory document
        org.apache.lucene.document.Document doc = documentBuilder.createDirectoryDocument(dir, attrs);
        Term pathTerm = DocumentBuilder.createPathTerm(dir);
        writer.updateDocument(pathTerm, doc);
    }
    
    /**
     * Indexes a single file.
     * 
     * @param writer the index writer
     * @param file the file to index
     * @throws IOException if an I/O error occurs
     */
    private void indexFile(IndexWriter writer, Path file) throws IOException {
        // Skip excluded files
        if (isExcluded(file)) {
            return;
        }
        
        BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
        
        // Create and add file document
        org.apache.lucene.document.Document doc = documentBuilder.createFileDocument(file, attrs);
        Term pathTerm = DocumentBuilder.createPathTerm(file);
        writer.updateDocument(pathTerm, doc);
    }
    
    /**
     * Deletes a document from the index.
     * 
     * @param writer the index writer
     * @param file the file to delete
     * @throws IOException if an I/O error occurs
     */
    private void deleteDocument(IndexWriter writer, Path file) throws IOException {
        Term pathTerm = DocumentBuilder.createPathTerm(file);
        writer.deleteDocuments(pathTerm);
    }
    
    /**
     * Indexes a folder and all its contents.
     * 
     * @param root the root folder to index
     * @return the number of documents indexed
     * @throws IOException if an I/O error occurs
     */
    public int indexFolder(Path root) throws IOException {
        Files.createDirectories(indexDir);
        
        try (Directory dir = FSDirectory.open(indexDir)) {
            IndexWriterConfig config = new IndexWriterConfig(analyzer)
                    .setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND)
                    .setRAMBufferSizeMB(256);
            
            AtomicInteger count = new AtomicInteger(0);
            
            try (IndexWriter writer = new IndexWriter(dir, config)) {
                Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<Path>() {
                    @Override
                    public java.nio.file.FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) 
                            throws IOException {
                        if (isExcluded(dir)) {
                            return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                        }
                        
                        indexDirectory(writer, dir);
                        count.incrementAndGet();
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }
                    
                    @Override
                    public java.nio.file.FileVisitResult visitFile(Path file, BasicFileAttributes attrs) 
                            throws IOException {
                        if (isExcluded(file)) {
                            return java.nio.file.FileVisitResult.CONTINUE;
                        }
                        
                        indexFile(writer, file);
                        count.incrementAndGet();
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }
                });
                
                writer.commit();
            }
            
            return count.get();
        }
    }
    
    /**
     * Indexes multiple folders.
     * 
     * @param roots the list of root folders to index
     * @param full whether to rebuild the index from scratch
     * @return the number of documents indexed
     * @throws IOException if an I/O error occurs
     */
    public int indexFolders(List<Path> roots, boolean full) throws IOException {
        Files.createDirectories(indexDir);
        
        try (Directory dir = FSDirectory.open(indexDir)) {
            IndexWriterConfig config = new IndexWriterConfig(analyzer)
                    .setOpenMode(full ? IndexWriterConfig.OpenMode.CREATE : IndexWriterConfig.OpenMode.CREATE_OR_APPEND)
                    .setRAMBufferSizeMB(256);
            
            AtomicInteger count = new AtomicInteger(0);
            
            try (IndexWriter writer = new IndexWriter(dir, config)) {
                for (Path root : roots) {
                    if (root == null) continue;
                    indexFolder(writer, root, count);
                }
                
                writer.commit();
            }
            
            return count.get();
        }
    }
    
    /**
     * Indexes a folder and its contents using the specified writer.
     * 
     * @param writer the index writer
     * @param root the root folder to index
     * @param count counter for indexed documents
     * @throws IOException if an I/O error occurs
     */
    private void indexFolder(IndexWriter writer, Path root, AtomicInteger count) throws IOException {
        Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<Path>() {
            @Override
            public java.nio.file.FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) 
                    throws IOException {
                if (isExcluded(dir)) {
                    return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                }
                
                indexDirectory(writer, dir);
                count.incrementAndGet();
                return java.nio.file.FileVisitResult.CONTINUE;
            }
            
            @Override
            public java.nio.file.FileVisitResult visitFile(Path file, BasicFileAttributes attrs) 
                    throws IOException {
                if (isExcluded(file)) {
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
                
                indexFile(writer, file);
                count.incrementAndGet();
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }
    
    /**
     * Checks if a path should be excluded from indexing.
     * 
     * @param path the path to check
     * @return true if the path should be excluded
     */
    private boolean isExcluded(Path path) {
        String unix = path.toString().replace('\\', '/');
        
        for (String glob : settings.excludeGlob) {
            try {
                java.nio.file.PathMatcher matcher = 
                    java.nio.file.FileSystems.getDefault().getPathMatcher("glob:" + glob);
                if (matcher.matches(path)) {
                    return true;
                }
            } catch (Exception ignore) {
                // Invalid glob pattern, ignore
            }
            
            // Fallback: simple contains check for **/xxx/** patterns
            String hint = glob.replace("**/", "").replace("/**", "");
            if (!hint.isEmpty() && unix.contains(hint)) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Creates the analyzer for indexing.
     * 
     * @return the configured analyzer
     */
    private Analyzer createAnalyzer() {
        Analyzer standard = new StandardAnalyzer();
        Analyzer chinese = new SmartChineseAnalyzer();
        Analyzer japanese = new JapaneseAnalyzer();
        
        java.util.Map<String, Analyzer> perField = new java.util.HashMap<>();
        perField.put("name", standard);
        perField.put("content", standard);
        perField.put("content_zh", chinese);
        perField.put("content_ja", japanese);
        
        return new PerFieldAnalyzerWrapper(standard, perField);
    }
}

