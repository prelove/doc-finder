package org.abitware.docfinder.index;

import org.abitware.docfinder.util.Utils;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.analysis.ja.JapaneseAnalyzer;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.store.FSDirectory;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lucene 索引器：
 * - 索引字段：path(规范化)、name、name_raw(小写，不分词)、ext、mtime_l(可范围过滤)、mtime/ctime/atime/size、mime、content(+zh/ja)
 * - 解析正文：只读、限时、大小/类型白名单
 * - 支持：单文件 upsert、删除；单目录/多目录全量索引（支持强制重建）
 */
public class LuceneIndexer implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(LuceneIndexer.class);
    private static final String KIND_FILE = "file";
    private static final String KIND_FOLDER = "folder";
    private static final int MAX_CHARS = 1_000_000;
    private static final List<String> DEFAULT_EXCLUDED_HINTS = Arrays.asList(
            "/$recycle.bin/",
            "/$recycle.bin",
            "/system volume information/",
            "/system volume information",
            "/recycler/",
            "/recycler"
    );

    /** Tika 解析器（自动探测） */
    private final AutoDetectParser tikaParser = new AutoDetectParser();

    /** 索引目录、配置 */
    private final Path indexDir;
    private final IndexSettings settings;

    /** 按字段选择分析器 */
    private final Analyzer perFieldAnalyzer;

    /** 共享的 IndexWriter 实例 */
    private final IndexWriter writer;

    public LuceneIndexer(Path indexDir, IndexSettings settings) throws IOException {
        this.indexDir = indexDir;
        this.settings = settings;

        Analyzer std = new StandardAnalyzer();
        Analyzer zh = new SmartChineseAnalyzer();
        Analyzer ja = new JapaneseAnalyzer();

        Map<String, Analyzer> perField = new HashMap<>();
        perField.put("name", std);
        perField.put("content", std);
        perField.put("content_zh", zh);
        perField.put("content_ja", ja);

        this.perFieldAnalyzer = new PerFieldAnalyzerWrapper(std, perField);

        Files.createDirectories(indexDir);
        IndexWriterConfig cfg = new IndexWriterConfig(perFieldAnalyzer)
                .setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND)
                .setRAMBufferSizeMB(256);
        this.writer = new IndexWriter(FSDirectory.open(indexDir), cfg);
    }

    /* --------------------------- 增/改/删 --------------------------- */

    /**
     * 单文件 upsert（新增或修改）；若文件不存在则等价 delete。
     */
    public void upsertFile(Path file) {
        if (file == null) return;
        try {
            if (!Files.exists(file)) {
                deletePath(file);
                return;
            }
            if (isExcluded(file)) return;

            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
            if (attrs.isDirectory()) {
                indexDirectory(this.writer, file, attrs);
            } else {
                indexFile(this.writer, file, attrs);
            }
        } catch (Throwable t) {
            log.warn("Upsert file error: {}, exception: {}", file, t.getMessage());
        }
    }

    /**
     * 删除一个绝对路径对应的文档（按规范化 path 匹配）。
     */
    public void deletePath(Path file) {
        if (file == null) return;
        try {
            String pathStr = Utils.normalizeForIndex(file);
            this.writer.deleteDocuments(new Term("path", pathStr));
        } catch (Throwable t) {
            log.warn("Delete path error: {}, exception: {}", file, t.getMessage());
        }
    }

    /**
     * 提交所有挂起的索引更改。
     */
    public void commit() throws IOException {
        writer.commit();
    }

    @Override
    public void close() throws IOException {
        if (writer != null) {
            writer.close();
        }
        if (perFieldAnalyzer != null) {
            perFieldAnalyzer.close();
        }
    }

    /* --------------------------- 批量索引 --------------------------- */

    /**
     * 全量索引一个根目录。
     */
    public int indexFolder(Path root) throws IOException {
        AtomicInteger count = new AtomicInteger(0);
        walkOneRoot(this.writer, root, count);
        writer.commit();
        return count.get();
    }

    /**
     * 多目录全量索引；full=true 表示强制重建（CREATE，覆盖旧索引）。
     */
    public int indexFolders(List<Path> roots, boolean full) throws IOException {
        if (full) {
            writer.deleteAll();
            writer.commit();
        }

        AtomicInteger count = new AtomicInteger(0);
        if (roots != null) {
            for (Path root : roots) {
                if (root == null) continue;
                walkOneRoot(this.writer, root, count);
            }
        }
        writer.commit();
        return count.get();
    }

    private void walkOneRoot(IndexWriter writer, Path root, AtomicInteger count) {
        try {
            if (!Files.exists(root)) {
                log.warn("Root path does not exist: {}", root);
                return;
            }
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    try {
                        if (isExcluded(dir)) return FileVisitResult.SKIP_SUBTREE;
                        indexDirectory(writer, dir, attrs);
                        count.incrementAndGet();
                    } catch (Throwable t) {
                        log.warn("Pre-visit directory error: {}, exception: {}", dir, t.getMessage());
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    try {
                        if (isExcluded(file)) return FileVisitResult.CONTINUE;
                        indexFile(writer, file, attrs);
                        count.incrementAndGet();
                    } catch (Throwable t) {
                        log.warn("Visit file error: {}, exception: {}", file, t.getMessage());
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    log.warn("Visit file failed: {}, exception: {}", file, exc != null ? exc.getMessage() : "unknown");
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                    if (exc != null) {
                        log.warn("Post-visit directory error: {}, exception: {}", dir, exc.getMessage());
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (Throwable t) {
            log.error("Walk tree error for root {}: {}", root, t.getMessage());
        }
    }

    /* --------------------------- 核心索引逻辑 --------------------------- */

    private void indexDirectory(IndexWriter writer, Path dir, BasicFileAttributes attrs) {
        try {
            String pathStr = Utils.normalizeForIndex(dir);
            String name = dir.getFileName() == null ? dir.toString() : dir.getFileName().toString();

            Document doc = new Document();
            doc.add(new StringField("path", pathStr, Field.Store.YES));
            doc.add(new TextField("name", name, Field.Store.YES));
            doc.add(new StringField("name_raw", name.toLowerCase(Locale.ROOT), Field.Store.NO));
            doc.add(new StringField("ext", "", Field.Store.YES));
            doc.add(new StringField("kind", KIND_FOLDER, Field.Store.YES));
            doc.add(new LongPoint("mtime_l", attrs.lastModifiedTime().toMillis()));
            doc.add(new StoredField("mtime", attrs.lastModifiedTime().toMillis()));
            doc.add(new StoredField("size", 0L));
            doc.add(new StoredField("ctime", attrs.creationTime().toMillis()));
            doc.add(new StoredField("atime", attrs.lastAccessTime().toMillis()));

            writer.updateDocument(new Term("path", pathStr), doc);
        } catch (Throwable t) {
            log.warn("Index directory error: {}, exception: {}", dir, t.getMessage());
        }
    }

    private void indexFile(IndexWriter writer, Path file, BasicFileAttributes attrs) {
        try {
            String name = file.getFileName() == null ? file.toString() : file.getFileName().toString();
            // 排除二进制执行文件
            if (name.endsWith(".exe") || name.endsWith(".dll")) return;

            String pathStr = Utils.normalizeForIndex(file);

            Document doc = new Document();
            doc.add(new StringField("path", pathStr, Field.Store.YES));
            doc.add(new TextField("name", name, Field.Store.YES));
            doc.add(new StringField("name_raw", name.toLowerCase(Locale.ROOT), Field.Store.NO));
            doc.add(new StringField("ext", getExt(name), Field.Store.YES));
            doc.add(new StringField("kind", KIND_FILE, Field.Store.YES));
            doc.add(new LongPoint("mtime_l", attrs.lastModifiedTime().toMillis()));
            doc.add(new StoredField("mtime", attrs.lastModifiedTime().toMillis()));
            doc.add(new StoredField("size", attrs.size()));
            doc.add(new StoredField("ctime", attrs.creationTime().toMillis()));
            doc.add(new StoredField("atime", attrs.lastAccessTime().toMillis()));

            String mime = null;
            String content = "";
            try {
                if (shouldParseContent(file, name, attrs.size())) {
                    content = extractTextReadOnly(file);
                }
                mime = Files.probeContentType(file);
            } catch (Throwable e) {
                log.warn("Get mime/content error: {}, exception: {}", file, e.getMessage());
            }

            if (mime != null) doc.add(new StringField("mime", mime, Field.Store.YES));
            if (!content.isEmpty()) {
                doc.add(new TextField("content", content, Field.Store.NO));
                doc.add(new TextField("content_zh", content, Field.Store.NO));
                doc.add(new TextField("content_ja", content, Field.Store.NO));
            }

            writer.updateDocument(new Term("path", pathStr), doc);
        } catch (Throwable t) {
            log.warn("Index file error: {}, exception: {}", file, t.getMessage());
        }
    }

    /* --------------------------- 规则/工具 --------------------------- */

    private boolean isExcluded(Path p) {
        try {
            String unix = p.toString().replace('\\', '/');
            String lower = unix.toLowerCase(Locale.ROOT);
            for (String hint : DEFAULT_EXCLUDED_HINTS) {
                if (lower.contains(hint)) return true;
            }
            if (settings.excludeGlob == null) return false;
            for (String g : settings.excludeGlob) {
                try {
                    PathMatcher m = FileSystems.getDefault().getPathMatcher("glob:" + g);
                    if (m.matches(p)) return true;
                } catch (Throwable e) {
                    log.warn("Invalid glob pattern: {}, exception: {}", g, e.getMessage());
                }
                // 兜底判断
                String hint = g.replace("**/", "").replace("/**", "");
                if (!hint.isEmpty() && unix.contains(hint)) return true;
            }
        } catch (Throwable t) {
            log.warn("isExcluded check error: {}, exception: {}", p, t.getMessage());
        }
        return false;
    }

    private static String getExt(String name) {
        int i = name.lastIndexOf('.');
        return (i > 0) ? name.substring(i + 1).toLowerCase(Locale.ROOT) : "";
    }

    private String extractTextReadOnly(Path file) {
        ExecutorService es = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "tika-extract");
            t.setDaemon(true);
            return t;
        });
        try {
            Future<String> fut = es.submit(() -> {
                try (InputStream is = Files.newInputStream(file, StandardOpenOption.READ)) {
                    Metadata md = new Metadata();
                    md.set(org.apache.tika.metadata.TikaCoreProperties.RESOURCE_NAME_KEY, file.getFileName().toString());
                    BodyContentHandler handler = new BodyContentHandler(MAX_CHARS);
                    ParseContext ctx = new ParseContext();
                    tikaParser.parse(is, handler, md, ctx);
                    return handler.toString();
                } catch (Throwable e) {
                    log.warn("Tika parse failed for {}: {}", file, e.getMessage());
                    return "";
                }
            });
            return fut.get(settings.parseTimeoutSec, TimeUnit.SECONDS);
        } catch (Throwable t) {
            log.warn("Extract text timeout or error: {}, exception: {}", file, t.getMessage());
            return "";
        } finally {
            es.shutdownNow();
        }
    }

    private boolean shouldParseContent(Path file, String name, long sizeBytes) {
        if (sizeBytes <= 0 || sizeBytes > settings.maxFileMB * 1024L * 1024L) return false;
        String ext = getExt(name);
        if (settings.includeExt != null && settings.includeExt.contains(ext)) return true;
        if (!settings.parseTextLike) return false;
        if (settings.textExts != null && settings.textExts.contains(ext)) return true;
        if (isTextMime(file)) return true;
        return looksLikeText(file);
    }

    private boolean isTextMime(Path file) {
        try {
            String mime = Files.probeContentType(file);
            if (mime == null) return false;
            mime = mime.toLowerCase(Locale.ROOT);
            return mime.startsWith("text/")
                    || mime.equals("application/json")
                    || mime.equals("application/xml")
                    || mime.equals("application/x-yaml")
                    || mime.equals("application/yaml")
                    || mime.equals("application/javascript")
                    || mime.equals("application/x-javascript")
                    || mime.equals("application/x-sh")
                    || mime.equals("application/x-java-source");
        } catch (Throwable e) {
            log.warn("MIME probe failed for {}: {}", file, e.getMessage());
            return false;
        }
    }

    private boolean looksLikeText(Path file) {
        byte[] buf = new byte[4096];
        try (InputStream is = Files.newInputStream(file, StandardOpenOption.READ)) {
            int n = is.read(buf);
            if (n <= 0) return false;
            int printable = 0;
            for (int i = 0; i < n; i++) {
                int b = buf[i] & 0xFF;
                if (b == 0x00) return false;
                if (b == 0x09 || b == 0x0A || b == 0x0D) printable++;
                else if (b >= 0x20 && b <= 0x7E) printable++;
            }
            double ratio = printable / (double) n;
            return ratio >= 0.85;
        } catch (Throwable e) {
            log.warn("Text sniffing failed for {}: {}", file, e.getMessage());
            return false;
        }
    }
}
