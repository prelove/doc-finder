package org.abitware.docfinder.index.content;

import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.abitware.docfinder.index.IndexSettings;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.TextField;
import org.apache.lucene.document.StringField;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;

/**
 * Content extractor that uses Apache Tika to extract text content from files.
 * This class handles read-only content extraction with timeout and size limits.
 * 
 * @author DocFinder Team
 * @version 1.0
 * @since 1.0
 */
public class ContentExtractor {
    
    /** Maximum characters to extract from a file */
    private static final int MAX_CHARS = 1_000_000;
    
    /** Tika parser for automatic content type detection */
    private final AutoDetectParser tikaParser;
    
    /** Timeout in seconds for content extraction */
    private final int timeoutSeconds;
    
    /** Maximum file size in bytes to attempt extraction */
    private final long maxFileSizeBytes;
    
    /** Index settings for configuration */
    private final IndexSettings settings;

    /**
     * Constructs a ContentExtractor with the specified settings.
     *
     * @param settings the index settings
     */
    public ContentExtractor(IndexSettings settings) {
        this.settings = settings;
        this.tikaParser = new AutoDetectParser();
        this.timeoutSeconds = settings.parseTimeoutSec;
        this.maxFileSizeBytes = settings.maxFileMB * 1024 * 1024;
    }

    /**
     * Constructs a ContentExtractor with the specified settings.
     * 
     * @param timeoutSeconds timeout in seconds for content extraction
     * @param maxFileSizeMB maximum file size in megabytes to process
     */
    public ContentExtractor(int timeoutSeconds, long maxFileSizeMB) {
        this.settings = null;
        this.tikaParser = new AutoDetectParser();
        this.timeoutSeconds = timeoutSeconds;
        this.maxFileSizeBytes = maxFileSizeMB * 1024 * 1024;
    }
    
    /**
     * Extracts text content from a file and adds it to the document.
     * The extraction is performed read-only with timeout protection.
     * 
     * @param file the file to extract content from
     * @param doc the Lucene document to add content to
     * @param fileName the file name for metadata
     * @return true if content was successfully extracted and added
     */
    public boolean extractAndAddToDocument(Path file, Document doc, String fileName) {
        // Check file size limit
        if (!isFileSizeValid(file)) {
            return false;
        }
        
        // Extract content with timeout
        String content = extractTextWithTimeout(file, fileName);
        if (content == null || content.trim().isEmpty()) {
            return false;
        }
        
        // Add content fields to document
        addContentFields(doc, content);
        return true;
    }
    
    /**
     * Determines if content should be parsed for a given file.
     *
     * @param filePath the file path
     * @param fileName the file name
     * @param size the file size
     * @return true if content should be parsed
     */
    public boolean shouldParseContent(Path filePath, String fileName, long size) {
        if (size <= 0 || size > maxFileSizeBytes) {
            return false;
        }

        if (settings == null) {
            return true; // If no settings, parse by default
        }

        // Get extension
        String ext = "";
        int dotIdx = fileName.lastIndexOf('.');
        if (dotIdx > 0) {
            ext = fileName.substring(dotIdx + 1).toLowerCase(java.util.Locale.ROOT);
        }

        // Check if in includeExt list (document types)
        if (settings.includeExt.contains(ext)) {
            return true;
        }

        // Check if in textExts list (text-like files)
        if (settings.parseTextLike && settings.textExts.contains(ext)) {
            return size <= settings.textMaxBytes;
        }

        return false;
    }

    /**
     * Extracts text content from a file.
     *
     * @param file the file to extract from
     * @return the extracted text, or empty string if extraction failed
     */
    public String extractText(Path file) {
        if (file == null) {
            return "";
        }

        String fileName = file.getFileName() != null ? file.getFileName().toString() : "";
        String content = extractTextWithTimeout(file, fileName);
        return content != null ? content : "";
    }

    /**
     * Checks if the file size is within the configured limit.
     * 
     * @param file the file to check
     * @return true if file size is valid for extraction
     */
    private boolean isFileSizeValid(Path file) {
        try {
            long size = Files.size(file);
            return size > 0 && size <= maxFileSizeBytes;
        } catch (Exception ex) {
            return false;
        }
    }
    
    /**
     * Extracts text content from a file with timeout protection.
     * 
     * @param file the file to extract from
     * @param fileName the file name for metadata
     * @return the extracted text, or null if extraction failed
     */
    private String extractTextWithTimeout(Path file, String fileName) {
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "tika-extract");
            t.setDaemon(true);
            return t;
        });
        
        try {
            Future<String> future = executor.submit(() -> {
                try (InputStream is = Files.newInputStream(file, StandardOpenOption.READ)) {
                    Metadata metadata = new Metadata();
                    metadata.set(org.apache.tika.metadata.TikaCoreProperties.RESOURCE_NAME_KEY, fileName);
                    BodyContentHandler handler = new BodyContentHandler(MAX_CHARS);
                    ParseContext context = new ParseContext();
                    tikaParser.parse(is, handler, metadata, context);
                    return handler.toString();
                } catch (Throwable e) {
                    return "";
                }
            });
            
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (Exception ex) {
            return null;
        } finally {
            executor.shutdownNow();
        }
    }
    
    /**
     * Adds content fields to the Lucene document.
     * 
     * @param doc the document to add fields to
     * @param content the content to add
     */
    private void addContentFields(Document doc, String content) {
        // Add analyzed content for general search
        doc.add(new TextField("content", content, Field.Store.NO));
        
        // Add content for Chinese analysis
        doc.add(new TextField("content_zh", content, Field.Store.NO));
        
        // Add content for Japanese analysis
        doc.add(new TextField("content_ja", content, Field.Store.NO));
    }
    
    /**
     * Attempts to read text content directly from a file using various charsets.
     * This is used as a fallback when Tika extraction fails or is not applicable.
     * 
     * @param file the file to read
     * @param maxChars maximum characters to read
     * @return the text content, or empty string if reading failed
     */
    public String readTextFallback(Path file, int maxChars) {
        LinkedHashSet<Charset> candidates = new LinkedHashSet<>();
        
        // Try BOM-detected charset first
        Charset bomCharset = detectBomCharset(file);
        if (bomCharset != null) {
            candidates.add(bomCharset);
        }
        
        // Add common charsets in order of preference
        candidates.add(StandardCharsets.UTF_8);
        candidates.add(StandardCharsets.UTF_16LE);
        candidates.add(StandardCharsets.UTF_16BE);
        
        // Add platform-specific charset as last resort
        try {
            candidates.add(Charset.forName("windows-1252"));
        } catch (Exception ignore) {
            // Ignore if charset is not available
        }
        
        // Try each charset until successful
        for (Charset charset : candidates) {
            if (charset == null) continue;
            
            try {
                String text = readTextWithCharset(file, maxChars, charset);
                if (text != null && !text.trim().isEmpty()) {
                    return text;
                }
            } catch (Exception ignore) {
                // Continue to next charset
            }
        }
        
        return "";
    }
    
    /**
     * Reads text from a file using the specified charset.
     * 
     * @param file the file to read
     * @param maxChars maximum characters to read
     * @param charset the charset to use
     * @return the text content
     * @throws Exception if reading fails
     */
    private String readTextWithCharset(Path file, int maxChars, Charset charset) throws Exception {
        if (file == null || charset == null || maxChars <= 0) {
            return "";
        }
        
        char[] buffer = new char[4096];
        StringBuilder sb = new StringBuilder(Math.min(maxChars, 65536));
        
        try (java.io.Reader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(
                    Files.newInputStream(file, StandardOpenOption.READ), charset))) {
            
            int remaining = maxChars;
            while (remaining > 0) {
                int n = reader.read(buffer, 0, Math.min(buffer.length, remaining));
                if (n < 0) break;
                sb.append(buffer, 0, n);
                remaining -= n;
            }
        }
        
        // Remove BOM if present
        if (sb.length() > 0 && sb.charAt(0) == '\uFEFF') {
            sb.deleteCharAt(0);
        }
        
        return sb.toString();
    }
    
    /**
     * Detects charset from BOM (Byte Order Mark) in the file.
     * 
     * @param file the file to check
     * @return the detected charset, or null if no BOM found
     */
    private Charset detectBomCharset(Path file) {
        if (file == null) return null;
        
        try (InputStream is = Files.newInputStream(file, StandardOpenOption.READ)) {
            byte[] bom = new byte[3];
            int n = is.read(bom);
            
            // Check for UTF-8 BOM
            if (n >= 3 && bom[0] == (byte) 0xEF && bom[1] == (byte) 0xBB && bom[2] == (byte) 0xBF) {
                return StandardCharsets.UTF_8;
            }
            
            // Check for UTF-16 BE BOM
            if (n >= 2 && bom[0] == (byte) 0xFE && bom[1] == (byte) 0xFF) {
                return StandardCharsets.UTF_16BE;
            }
            
            // Check for UTF-16 LE BOM
            if (n >= 2 && bom[0] == (byte) 0xFF && bom[1] == (byte) 0xFE) {
                return StandardCharsets.UTF_16LE;
            }
            
        } catch (Exception ignore) {
            // Ignore detection errors
        }
        
        return null;
    }
}