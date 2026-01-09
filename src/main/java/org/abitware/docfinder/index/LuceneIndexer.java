package org.abitware.docfinder.index;

import org.abitware.docfinder.util.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.analysis.ja.JapaneseAnalyzer;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;

import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lucene 索引器：
 * - 索引字段：path(规范化)、name、name_raw(小写，不分词)、ext、mtime_l(可范围过滤)、mtime/ctime/atime/size、mime、content(+zh/ja)
 * - 解析正文：只读、限时、大小/类型白名单
 * - 支持：单文件 upsert、删除；单目录/多目录全量索引（支持强制重建）
 */
public class LuceneIndexer implements AutoCloseable { // Implements AutoCloseable
    private static final Logger log = LoggerFactory.getLogger(LuceneIndexer.class);
    private static final String KIND_FILE = "file";
    private static final String KIND_FOLDER = "folder";


    /** Tika 解析器（自动探测） */
    private final AutoDetectParser tikaParser = new AutoDetectParser();

    /** 单文件抽取字符上限（BodyContentHandler 限制） */
    private static final int MAX_CHARS = 1_000_000;

    /** 索引目录、配置 */
    private final Path indexDir;
    private final IndexSettings settings;

    /** 按字段选择分析器：name/content 用 Standard，中文/日文用各自分词 */
    private final Analyzer perFieldAnalyzer;

    // NEW FIELD: The single IndexWriter instance
    private final IndexWriter writer;

    public LuceneIndexer(Path indexDir, IndexSettings settings) throws IOException { // Constructor now throws IOException
        this.indexDir = indexDir;
        this.settings = settings;

        Analyzer std = new StandardAnalyzer();
        Analyzer zh  = new SmartChineseAnalyzer();
        Analyzer ja  = new JapaneseAnalyzer();

        Map<String, Analyzer> perField = new HashMap<>();
        perField.put("name", std);
        perField.put("content", std);
        perField.put("content_zh", zh);
        perField.put("content_ja", ja);

        this.perFieldAnalyzer = new PerFieldAnalyzerWrapper(std, perField);

        // Initialize the single IndexWriter
        Files.createDirectories(indexDir);
        IndexWriterConfig cfg = new IndexWriterConfig(perFieldAnalyzer)
                .setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND)
                .setRAMBufferSizeMB(256); // Use the buffer size from settings or a default
        this.writer = new IndexWriter(FSDirectory.open(indexDir), cfg);
    }

    /* --------------------------- 增/改/删 --------------------------- */

    /**
     * 单文件 upsert（新增或修改）；若文件不存在则等价 delete。
     * - 使用规范化 path 作为文档主键（Term("path", pathStr)）。
     * - 写入 name_raw（小写、不分词），供 name: 通配/精确匹配使用。
     * - 正文抽取为只读+限时，避免修改文件时间或被占用。
     */
    public void upsertFile(Path file) throws IOException {
        if (file == null) return;
        if (!Files.exists(file)) { deletePath(file); return; }
        if (isExcluded(file)) return;

        // Use the shared writer
        BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
        if (attrs.isDirectory()) {
            indexDirectory(this.writer, file, attrs);
            // writer.commit(); // REMOVED: commit is now handled by caller
            return;
        }

        String name = file.getFileName().toString();
        String pathStr = Utils.normalizeForIndex(file);

        Document doc = new Document();
        doc.add(new StringField("path", pathStr, Field.Store.YES));
        doc.add(new TextField("name", name, Field.Store.YES));
        doc.add(new StringField("name_raw", name.toLowerCase(java.util.Locale.ROOT), Field.Store.NO));
        doc.add(new StringField("ext", getExt(name), Field.Store.YES));
        doc.add(new StringField("kind", KIND_FILE, Field.Store.YES));
        doc.add(new LongPoint("mtime_l", attrs.lastModifiedTime().toMillis()));
        doc.add(new StoredField("mtime", attrs.lastModifiedTime().toMillis()));
        doc.add(new StoredField("size", attrs.size()));
        doc.add(new StoredField("ctime", attrs.creationTime().toMillis()));
        doc.add(new StoredField("atime", attrs.lastAccessTime().toMillis()));

        String mime = null, content = "";
        try {
            if (shouldParseContent(file, name, attrs.size())) {
                content = extractTextReadOnly(file);
            }
            mime = Files.probeContentType(file);
        } catch (Exception e) {
            log.warn("Could not extract content/mime for {} [{}], skipping", file, e.getMessage());
        }

        if (mime != null) doc.add(new StringField("mime", mime, Field.Store.YES));
        if (!content.isEmpty()) {
            doc.add(new TextField("content", content, Field.Store.NO));
            doc.add(new TextField("content_zh", content, Field.Store.NO));
            doc.add(new TextField("content_ja", content, Field.Store.NO));
        }

        this.writer.updateDocument(new Term("path", pathStr), doc); // Use the shared writer
        // writer.commit(); // REMOVED: commit is now handled by caller
    }


    /**
     * 删除一个绝对路径对应的文档（按规范化 path 匹配）。
     */
    public void deletePath(Path file) throws IOException {
        if (file == null) return;
        // Use the shared writer
        String pathStr = Utils.normalizeForIndex(file); // ✅ 统一规范化
        this.writer.deleteDocuments(new Term("path", pathStr)); // Use the shared writer
        // writer.commit(); // REMOVED: commit is now handled by caller
    }

    /**
     * 提交所有挂起的索引更改。
     */
    public void commit() throws IOException {
        writer.commit();
    }

    @Override
    public void close() throws IOException {
        writer.close();
    }

    /**
     * 全量索引一个根目录（旧入口，保留向后兼容）。
     * - CREATE_OR_APPEND 模式：存在则更新，不存在则创建。
     * - 建议使用 indexFolders(...) 做多根/重建。
     */
    public int indexFolder(Path root) throws IOException {
        Files.createDirectories(indexDir);
        try (Directory dir = FSDirectory.open(indexDir)) {
            IndexWriterConfig cfg = new IndexWriterConfig(perFieldAnalyzer)
                    .setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND)
                    .setRAMBufferSizeMB(256);

            final int[] count = {0};
            try (IndexWriter writer = new IndexWriter(dir, cfg)) {
                Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                        if (isExcluded(dir)) return FileVisitResult.SKIP_SUBTREE;
                        indexDirectory(writer, dir, attrs);
                        count[0]++;
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        if (attrs.isDirectory() || isExcluded(file)) return FileVisitResult.CONTINUE;

                        String name = file.getFileName().toString();
                        if (name.endsWith(".exe") || name.endsWith(".dll")) return FileVisitResult.CONTINUE;

                        String pathStr = Utils.normalizeForIndex(file);

                        Document doc = new Document();
                        doc.add(new StringField("path", pathStr, Field.Store.YES));
                        doc.add(new TextField("name", name, Field.Store.YES));
                        doc.add(new StringField("name_raw", name.toLowerCase(java.util.Locale.ROOT), Field.Store.NO));
                        doc.add(new StringField("ext", getExt(name), Field.Store.YES));
                        doc.add(new StringField("kind", KIND_FILE, Field.Store.YES));
                        doc.add(new LongPoint("mtime_l", attrs.lastModifiedTime().toMillis()));
                        doc.add(new StoredField("mtime", attrs.lastModifiedTime().toMillis()));
                        doc.add(new StoredField("size", attrs.size()));
                        doc.add(new StoredField("ctime", attrs.creationTime().toMillis()));
                        doc.add(new StoredField("atime", attrs.lastAccessTime().toMillis()));

                        String mime = null, content = "";
                        try {
                            if (shouldParseContent(file, name, attrs.size())) {
                                content = extractTextReadOnly(file);
                            }
                            mime = java.nio.file.Files.probeContentType(file);
                        } catch (Exception e) {
                            log.warn("Could not extract content/mime for {} [{}], skipping", file, e.getMessage());
                        }

                        if (mime != null) doc.add(new StringField("mime", mime, Field.Store.YES));
                        if (!content.isEmpty()) {
                            doc.add(new TextField("content", content, Field.Store.NO));
                            doc.add(new TextField("content_zh", content, Field.Store.NO));
                            doc.add(new TextField("content_ja", content, Field.Store.NO));
                        }

                        writer.updateDocument(new Term("path", pathStr), doc);
                        count[0]++;
                        return FileVisitResult.CONTINUE;
                    }
                });
                writer.commit();
            }
            return count[0];
        }
    }


    /* --------------------------- 多目录索引（推荐入口） --------------------------- */

    /**
     * 多目录全量索引；full=true 表示强制重建（CREATE，覆盖旧索引）。
     */
    public int indexFolders(List<Path> roots, boolean full) throws IOException {
        Files.createDirectories(indexDir);
        try (Directory dir = FSDirectory.open(indexDir)) {
            IndexWriterConfig cfg = new IndexWriterConfig(perFieldAnalyzer)
                    .setOpenMode(full ? IndexWriterConfig.OpenMode.CREATE : IndexWriterConfig.OpenMode.CREATE_OR_APPEND)
                    .setRAMBufferSizeMB(256);

            AtomicInteger count = new AtomicInteger(0);
            try (IndexWriter writer = new IndexWriter(dir, cfg)) {
                for (Path root : roots) {
                    if (root == null) continue;
                    walkOneRoot(writer, root, count);
                }
                writer.commit();
            }
            return count.get();
        }
    }

    private void walkOneRoot(IndexWriter writer, Path root,
            java.util.concurrent.atomic.AtomicInteger count) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (isExcluded(dir)) return FileVisitResult.SKIP_SUBTREE;
                indexDirectory(writer, dir, attrs);
                count.incrementAndGet();
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (attrs.isDirectory() || isExcluded(file)) return FileVisitResult.CONTINUE;

                String name = file.getFileName().toString();
                if (name.endsWith(".exe") || name.endsWith(".dll")) return FileVisitResult.CONTINUE;

                String pathStr = org.abitware.docfinder.util.Utils.normalizeForIndex(file);

                Document doc = new Document();
                doc.add(new StringField("path", pathStr, Field.Store.YES));
                doc.add(new TextField("name", name, Field.Store.YES));
                doc.add(new StringField("name_raw", name.toLowerCase(java.util.Locale.ROOT), Field.Store.NO));
                doc.add(new StringField("ext", getExt(name), Field.Store.YES));
                doc.add(new StringField("kind", KIND_FILE, Field.Store.YES));
                doc.add(new LongPoint("mtime_l", attrs.lastModifiedTime().toMillis()));
                doc.add(new StoredField("mtime", attrs.lastModifiedTime().toMillis()));
                doc.add(new StoredField("size", attrs.size()));
                doc.add(new StoredField("ctime", attrs.creationTime().toMillis()));
                doc.add(new StoredField("atime", attrs.lastAccessTime().toMillis()));

                String mime = null, content = "";
                try {
                    if (shouldParseContent(file, name, attrs.size())) {
                        content = extractTextReadOnly(file);
                    }
                    mime = java.nio.file.Files.probeContentType(file);
                } catch (Exception e) {
                    log.warn("Could not extract content/mime for {} [{}], skipping", file, e.getMessage());
                }

                if (mime != null) doc.add(new StringField("mime", mime, Field.Store.YES));
                if (!content.isEmpty()) {
                    doc.add(new TextField("content", content, Field.Store.NO));
                    doc.add(new TextField("content_zh", content, Field.Store.NO));
                    doc.add(new TextField("content_ja", content, Field.Store.NO));
                }

                writer.updateDocument(new Term("path", pathStr), doc);
                count.incrementAndGet();
                return FileVisitResult.CONTINUE;
            }
        });
    }


    /* --------------------------- 规则/工具 --------------------------- */

    /**
     * 目录/文件排除：
     * - 使用 settings.excludeGlob 中的 glob 规则；
     * - 简单兜底：把路径转为 Unix 风格字符串做包含判断。
     */
    private boolean isExcluded(Path p) {
        String unix = p.toString().replace('\\', '/');
        for (String g : settings.excludeGlob) {
            try {
                PathMatcher m = FileSystems.getDefault().getPathMatcher("glob:" + g);
                if (m.matches(p)) return true;
            } catch (Exception e) {
                log.warn("Invalid glob pattern '{}' in settings, skipping", g);
            }
            // 兜底：**/xxx/** 的粗略包含判断
            String hint = g.replace("**/", "").replace("/**", "");
            if (!hint.isEmpty() && unix.contains(hint)) return true;
        }
        return false;
    }

    /** 取扩展名（小写；无扩展名返回空串） */
    private static String getExt(String name) {
        int i = name.lastIndexOf('.');
        return (i > 0) ? name.substring(i + 1).toLowerCase(Locale.ROOT) : "";
        }

    /**
     * 只读 + 限时抽取正文（不会修改文件时间；读取失败/超时返回空串）。
     */
    private String extractTextReadOnly(Path file) {
        ExecutorService es = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "tika-extract"); t.setDaemon(true); return t;
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
            return fut.get(settings.parseTimeoutSec, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception timeoutOrOther) {
            log.warn("Content extraction timed out or failed for {}: {}", file, timeoutOrOther.getMessage());
            return "";
        } finally {
            es.shutdownNow();
        }
    }

    /**
     * 是否需要解析正文：
     * 1) 大小阈值（settings.maxFileMB）；
     * 2) 文档白名单 includeExt 命中则解析；
     * 3) 若 settings.parseTextLike=true：text 类扩展名 / MIME / 启发式嗅探（其一命中即解析）
     */
    private boolean shouldParseContent(Path file, String name, long sizeBytes) {
        // 1) 大小限制
        if (sizeBytes <= 0 || sizeBytes > settings.maxFileMB * 1024L * 1024L) return false;

        String ext = getExt(name);

        // 2) 文档白名单（如 pdf, docx, xlsx, pptx, html…）
        if (settings.includeExt != null && settings.includeExt.contains(ext)) return true;

        // 3) 文本类
        if (!settings.parseTextLike) return false;
        if (settings.textExts != null && settings.textExts.contains(ext)) return true;
        if (isTextMime(file)) return true;
        return looksLikeText(file); // 4KB 嗅探：无 NUL 且可打印 ASCII 比例高
    }

    private void indexDirectory(IndexWriter writer, Path dir, BasicFileAttributes attrs) throws IOException {
        if (dir == null || attrs == null) return;
        String pathStr = Utils.normalizeForIndex(dir);
        String name = dir.getFileName() == null ? dir.toString() : dir.getFileName().toString();

        Document doc = new Document();
        doc.add(new StringField("path", pathStr, Field.Store.YES));
        doc.add(new TextField("name", name, Field.Store.YES));
        doc.add(new StringField("name_raw", name.toLowerCase(java.util.Locale.ROOT), Field.Store.NO));
        doc.add(new StringField("ext", "", Field.Store.YES));
        doc.add(new StringField("kind", KIND_FOLDER, Field.Store.YES));
        doc.add(new LongPoint("mtime_l", attrs.lastModifiedTime().toMillis()));
        doc.add(new StoredField("mtime", attrs.lastModifiedTime().toMillis()));
        doc.add(new StoredField("size", 0L));
        doc.add(new StoredField("ctime", attrs.creationTime().toMillis()));
        doc.add(new StoredField("atime", attrs.lastAccessTime().toMillis()));
        writer.updateDocument(new Term("path", pathStr), doc);
    }


    /** MIME 探测：text/* 或常见文本型 application/* */
    private boolean isTextMime(Path file) {
        try {
            String mime = Files.probeContentType(file);
            if (mime == null) return false;
            mime = mime.toLowerCase(Locale.ROOT);
            if (mime.startsWith("text/")) return true;
            // 常见文本型 application
            return mime.equals("application/json")
                || mime.equals("application/xml")
                || mime.equals("application/x-yaml")
                || mime.equals("application/yaml")
                || mime.equals("application/javascript")
                || mime.equals("application/x-javascript")
                || mime.equals("application/x-sh")
                || mime.equals("application/x-java-source");
        } catch (IOException e) {
            log.warn("MIME probe failed for {}: {}", file, e.getMessage());
            return false;
        }
    }

    /** 启发式文本判断：首 4KB 不含 NUL，且可打印字符比例 >= 0.85 */
    private boolean looksLikeText(Path file) {
        byte[] buf = new byte[4096];
        try (InputStream is = Files.newInputStream(file, StandardOpenOption.READ)) {
            int n = is.read(buf);
            if (n <= 0) return false;
            int printable = 0;
            for (int i = 0; i < n; i++) {
                int b = buf[i] & 0xFF;
                if (b == 0x00) return false;                      // NUL → 二进制概率高
                if (b == 0x09 || b == 0x0A || b == 0x0D) printable++;  // 制表/换行
                else if (b >= 0x20 && b <= 0x7E) printable++;          // 可打印 ASCII
            }
            double ratio = printable / (double) n;
            return ratio >= 0.85;
        } catch (IOException e) {
            log.warn("Text sniffing failed for {}: {}", file, e.getMessage());
            return false;
        }
    }
}