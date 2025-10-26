package org.abitware.docfinder.index.content;

import java.nio.file.Path;
import java.nio.file.Paths;
import org.abitware.docfinder.index.IndexSettings;
import org.abitware.docfinder.util.Utils;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.Term;

/**
 * Builder for creating Lucene documents from files.
 * This class encapsulates the logic for creating standardized Lucene documents
 * with all required fields and proper indexing.
 * 
 * @author DocFinder Team
 * @version 1.0
 * @since 1.0
 */
public class DocumentBuilder {
    
    /** Field names used in the index */
    private static final String FIELD_PATH = "path";
    private static final String FIELD_NAME = "name";
    private static final String FIELD_NAME_RAW = "name_raw";
    private static final String FIELD_EXT = "ext";
    private static final String FIELD_KIND = "kind";
    private static final String FIELD_MTIME_L = "mtime_l";
    private static final String FIELD_MTIME = "mtime";
    private static final String FIELD_CTIME = "ctime";
    private static final String FIELD_ATIME = "atime";
    private static final String FIELD_SIZE = "size";
    private static final String FIELD_MIME = "mime";
    private static final String FIELD_CONTENT = "content";
    private static final String FIELD_CONTENT_ZH = "content_zh";
    private static final String FIELD_CONTENT_JA = "content_ja";
    
    /** Kind constants */
    private static final String KIND_FILE = "file";
    private static final String KIND_FOLDER = "folder";
    
    /** Content extractor for text extraction */
    private final ContentExtractor contentExtractor;
    
    /**
     * Constructs a DocumentBuilder with the specified index settings.
     * 
     * @param settings the index settings
     */
    public DocumentBuilder(IndexSettings settings) {
        this.contentExtractor = new ContentExtractor(settings);
    }
    
    /**
     * Creates a Lucene document for a file.
     * 
     * @param filePath the path to the file
     * @param attrs the file attributes
     * @return the created Lucene document
     */
    public Document createFileDocument(Path filePath, java.nio.file.attribute.BasicFileAttributes attrs) {
        String name = filePath.getFileName().toString();
        String pathStr = Utils.normalizeForIndex(filePath);
        String ext = getExtension(name);
        
        Document doc = new Document();
        
        // Add basic fields
        addBasicFields(doc, pathStr, name, ext, KIND_FILE);
        
        // Add timestamp fields
        addTimestampFields(doc, attrs);
        
        // Add size field
        doc.add(new StoredField(FIELD_SIZE, attrs.size()));
        
        // Add MIME type
        addMimeTypeField(doc, filePath);
        
        // Add content if applicable
        addContentFields(doc, filePath, name, attrs.size());
        
        return doc;
    }
    
    /**
     * Creates a Lucene document for a directory.
     * 
     * @param dirPath the path to the directory
     * @param attrs the directory attributes
     * @return the created Lucene document
     */
    public Document createDirectoryDocument(Path dirPath, java.nio.file.attribute.BasicFileAttributes attrs) {
        String name = dirPath.getFileName() != null ? dirPath.getFileName().toString() : dirPath.toString();
        String pathStr = Utils.normalizeForIndex(dirPath);
        
        Document doc = new Document();
        
        // Add basic fields
        addBasicFields(doc, pathStr, name, "", KIND_FOLDER);
        
        // Add timestamp fields
        addTimestampFields(doc, attrs);
        
        // Add zero size for directories
        doc.add(new StoredField(FIELD_SIZE, 0L));
        
        return doc;
    }
    
    /**
     * Adds basic fields to a document.
     * 
     * @param doc the document to add fields to
     * @param pathStr the normalized path string
     * @param name the file/directory name
     * @param ext the file extension
     * @param kind the kind (file or folder)
     */
    private void addBasicFields(Document doc, String pathStr, String name, String ext, String kind) {
        // Path (primary key)
        doc.add(new StringField(FIELD_PATH, pathStr, org.apache.lucene.document.Field.Store.YES));
        
        // Name (analyzed for searching)
        doc.add(new TextField(FIELD_NAME, name, org.apache.lucene.document.Field.Store.YES));
        
        // Name raw (lowercase, not analyzed for exact/wildcard matching)
        doc.add(new StringField(FIELD_NAME_RAW, name.toLowerCase(java.util.Locale.ROOT), 
                              org.apache.lucene.document.Field.Store.NO));
        
        // Extension
        doc.add(new StringField(FIELD_EXT, ext, org.apache.lucene.document.Field.Store.YES));
        
        // Kind
        doc.add(new StringField(FIELD_KIND, kind, org.apache.lucene.document.Field.Store.YES));
    }
    
    /**
     * Adds timestamp fields to a document.
     * 
     * @param doc the document to add fields to
     * @param attrs the file attributes containing timestamps
     */
    private void addTimestampFields(Document doc, java.nio.file.attribute.BasicFileAttributes attrs) {
        long mtime = attrs.lastModifiedTime().toMillis();
        long ctime = attrs.creationTime().toMillis();
        long atime = attrs.lastAccessTime().toMillis();
        
        // Modifiable time for range queries
        doc.add(new LongPoint(FIELD_MTIME_L, mtime));
        
        // Timestamps for display
        doc.add(new StoredField(FIELD_MTIME, mtime));
        doc.add(new StoredField(FIELD_CTIME, ctime));
        doc.add(new StoredField(FIELD_ATIME, atime));
    }
    
    /**
     * Adds MIME type field to a document.
     * 
     * @param doc the document to add field to
     * @param filePath the file path
     */
    private void addMimeTypeField(Document doc, Path filePath) {
        try {
            String mime = java.nio.file.Files.probeContentType(filePath);
            if (mime != null) {
                doc.add(new StringField(FIELD_MIME, mime, org.apache.lucene.document.Field.Store.YES));
            }
        } catch (Exception ignore) {
            // MIME type detection is optional
        }
    }
    
    /**
     * Adds content fields to a document if content extraction is enabled.
     * 
     * @param doc the document to add fields to
     * @param filePath the file path
     * @param name the file name
     * @param size the file size
     */
    private void addContentFields(Document doc, Path filePath, String name, long size) {
        // Check if content should be extracted
        if (!contentExtractor.shouldParseContent(filePath, name, size)) {
            return;
        }
        
        // Extract content
        String content = contentExtractor.extractText(filePath);
        if (content != null && !content.isEmpty()) {
            // Add content for general search
            doc.add(new TextField(FIELD_CONTENT, content, org.apache.lucene.document.Field.Store.NO));
            
            // Add content for Chinese search
            doc.add(new TextField(FIELD_CONTENT_ZH, content, org.apache.lucene.document.Field.Store.NO));
            
            // Add content for Japanese search
            doc.add(new TextField(FIELD_CONTENT_JA, content, org.apache.lucene.document.Field.Store.NO));
        }
    }
    
    /**
     * Gets the file extension.
     * 
     * @param name the file name
     * @return the extension in lowercase, or empty string if no extension
     */
    private String getExtension(String name) {
        if (name == null) {
            return "";
        }
        
        int dot = name.lastIndexOf('.');
        return (dot > 0) ? name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT) : "";
    }
    
    /**
     * Creates a term for the document's path field.
     * This is used for document updates and deletions.
     * 
     * @param path the file path
     * @return the term for the path field
     */
    public static Term createPathTerm(Path path) {
        String pathStr = Utils.normalizeForIndex(path);
        return new Term(FIELD_PATH, pathStr);
    }
    
    /**
     * Creates a term for the document's path field.
     * This is used for document updates and deletions.
     * 
     * @param pathStr the normalized path string
     * @return the term for the path field
     */
    public static Term createPathTerm(String pathStr) {
        return new Term(FIELD_PATH, pathStr);
    }
}