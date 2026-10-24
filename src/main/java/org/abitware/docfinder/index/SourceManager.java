package org.abitware.docfinder.index;

import org.abitware.docfinder.util.Utils;
import org.abitware.docfinder.util.AppPaths;
import org.abitware.docfinder.util.PathScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

/** 管理“索引源”列表（带本地/网络标记）。文件格式：每行 path|0/1 （1=network） */
public class SourceManager {
    private static final Logger log = LoggerFactory.getLogger(SourceManager.class);
    private final Path baseDir;
    private final Path sourcesFile;
    private final Path indexDir;

    public SourceManager() {
        this(AppPaths.getBaseDir());
    }

    /** Visible for scoped-search/tests: baseDir decides where sources.txt lives. */
    public SourceManager(Path baseDir) {
        this.baseDir = baseDir;
        this.sourcesFile = baseDir.resolve("sources.txt"); // 向后兼容：无 | 时按旧格式读取
        this.indexDir = new ConfigManager().getIndexDir();
    }

    /** 数据模型：一个源目录 + 是否网络 */
    public static class SourceEntry {
        public String path;   // 绝对路径字符串
        public boolean network; // true=网络盘/UNC/映射盘；false=本地
        public SourceEntry() {}
        public SourceEntry(String path, boolean network) { this.path = path; this.network = network; }
    }

    public Path getIndexDir() { return indexDir; }

    /** 向后兼容读取：支持老的只有 path 一列的格式；遇到老行会自动判定 network 并在保存时写入新格式 */
    public List<SourceEntry> loadEntries() {
        List<SourceEntry> out = new ArrayList<>();
        try {
            if (!Files.exists(sourcesFile)) return out;
            List<String> lines = Files.readAllLines(sourcesFile, StandardCharsets.UTF_8);
            for (String line : lines) {
                String s = line.trim();
                if (s.isEmpty() || s.startsWith("#")) continue;
                String[] sp = s.split("\\|", -1);
                if (sp.length == 1) {
                    // 旧格式：仅有 path
                    String p = sp[0];
                    boolean net = Utils.isLikelyNetwork(Paths.get(p));
                    out.add(new SourceEntry(p, net));
                } else {
                    String p = sp[0];
                    boolean net = "1".equals(sp[1]);
                    out.add(new SourceEntry(p, net));
                }
            }
        } catch (IOException ignore) {}
        return out;
    }

    /** 保存为新格式 path|0/1 */
    public void saveEntries(List<SourceEntry> list) {
        try {
            Files.createDirectories(baseDir);
            List<String> lines = new ArrayList<>();
            for (SourceEntry e : list) {
                if (e.path == null || e.path.trim().isEmpty()) continue;
                String norm = Paths.get(e.path).toAbsolutePath().toString();
                lines.add(norm + "|" + (e.network ? "1" : "0"));
            }
            Files.write(sourcesFile, lines, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException ignore) {}
    }
    
    // 向后兼容：旧UI用的保存方法（只给路径），自动判定本地/网络并落盘为新格式 path|0/1
    public void save(java.util.List<java.nio.file.Path> roots) {
        java.util.List<SourceEntry> list = new java.util.ArrayList<>();
        if (roots != null) {
            for (java.nio.file.Path p : roots) {
                if (p == null) continue;
                // 绝对路径字符串（Windows 下也能兼容 ¥/全角 等，我们交给 Utils 处理）
                String abs = p.toAbsolutePath().toString();
                boolean net = org.abitware.docfinder.util.Utils.isLikelyNetwork(p);
                list.add(new SourceEntry(abs, net));
            }
        }
        saveEntries(list);
    }

    /** 便捷重载：若你手头是字符串列表（例如从 JList model 取出），也可以直接保存 */
    public void saveStrings(java.util.List<String> rootStrings) {
        java.util.List<SourceEntry> list = new java.util.ArrayList<>();
        if (rootStrings != null) {
            for (String s : rootStrings) {
                if (s == null || s.trim().isEmpty()) continue;
                // 规范化一下再转 Path（兼容日文 OS 输入的 ¥¥server 之类）
                String norm = org.abitware.docfinder.util.Utils.isWindows()
                        ? org.abitware.docfinder.util.Utils.canonicalizeWinPathString(s)
                        : s;
                java.nio.file.Path p = java.nio.file.Paths.get(norm);
                boolean net = org.abitware.docfinder.util.Utils.isLikelyNetwork(p);
                list.add(new SourceEntry(p.toAbsolutePath().toString(), net));
            }
        }
        saveEntries(list);
    }

    /** Fast load: 不做网络判定（老格式行默认 Local），交给 UI 异步检测后覆盖 */
    public List<SourceManager.SourceEntry> loadEntriesFast() {
        List<SourceManager.SourceEntry> out = new ArrayList<>();
        try {
            if (!Files.exists(sourcesFile)) return out;
            List<String> lines = Files.readAllLines(sourcesFile, StandardCharsets.UTF_8);
            for (String line : lines) {
                String s = line.trim();
                if (s.isEmpty() || s.startsWith("#")) continue;
                String[] sp = s.split("\\|", -1);
                if (sp.length == 1) {
                    out.add(new SourceEntry(sp[0], /*assumeLocal*/ false));
                } else {
                    out.add(new SourceEntry(sp[0], "1".equals(sp[1])));
                }
            }
        } catch (IOException ignore) {}
        return out;
    }

    /** 兼容旧代码：仅返回路径（不区分本地/网络） */
    public List<Path> load() {
        return loadEntries().stream()
                .map(e -> Paths.get(e.path))
                .collect(Collectors.toList());
    }

    /**
     * Strict search-time view of configured sources: reads only, never writes,
     * never probes the network. Bad lines are logged with line number and
     * skipped; I/O failure or a file whose data lines are all invalid raises
     * IOException instead of being swallowed into an empty configuration.
     */
    public List<Path> loadSearchRoots() throws IOException {
        return readSearchRoots(sourcesFile);
    }

    static List<Path> readSearchRoots(Path file) throws IOException {
        return readSearchRootsSnapshot(file).roots;
    }

    /**
     * Same strict parse as {@link #loadSearchRoots()} but also exposes the
     * skipped line numbers so the desktop can show a one-time warning.
     * Web/HTTP never receives skippedLineNumbers.
     */
    public SearchRootsSnapshot loadSearchRootsSnapshot() throws IOException {
        return readSearchRootsSnapshot(sourcesFile);
    }

    /** Immutable result of a strict source-file parse. */
    public static final class SearchRootsSnapshot {
        public final List<Path> roots;
        public final List<Integer> skippedLineNumbers;

        SearchRootsSnapshot(List<Path> roots, List<Integer> skippedLineNumbers) {
            this.roots = roots;
            this.skippedLineNumbers = skippedLineNumbers;
        }
    }

    static SearchRootsSnapshot readSearchRootsSnapshot(Path file) throws IOException {
        if (file == null) {
            return new SearchRootsSnapshot(Collections.emptyList(), Collections.emptyList());
        }
        List<String> lines;
        try {
            // Only a definite "file does not exist" means no sources. Any
            // other I/O failure (access denied, unreadable parent, ...)
            // propagates so callers report SOURCE_CONFIG_UNAVAILABLE rather
            // than silently treating sources as empty (QA24-05).
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return new SearchRootsSnapshot(Collections.emptyList(), Collections.emptyList());
        }
        List<Path> out = new ArrayList<>();
        List<Integer> skipped = new ArrayList<>();
        int dataLines = 0;
        for (int i = 0; i < lines.size(); i++) {
            String s = lines.get(i).trim();
            if (s.isEmpty() || s.startsWith("#")) {
                continue;
            }
            dataLines++;
            String[] sp = s.split("\\|", -1);
            String reason = null;
            if (sp.length > 2) {
                reason = "too many columns";
            } else if (sp.length == 2 && !("0".equals(sp[1].trim()) || "1".equals(sp[1].trim()))) {
                reason = "invalid network flag";
            }
            if (reason == null) {
                String raw = sp[0].trim();
                try {
                    if (PathScope.normalizeDirectory(raw) == null) {
                        reason = "empty path column";
                    }
                } catch (IllegalArgumentException e) {
                    reason = "invalid absolute path: " + e.getMessage();
                }
                if (reason == null) {
                    String canon = Utils.isWindows()
                            ? Utils.canonicalizeWinPathString(raw) : raw;
                    try {
                        out.add(Paths.get(canon));
                    } catch (InvalidPathException e) {
                        reason = "invalid path syntax: " + e.getMessage();
                    }
                }
            }
            if (reason != null) {
                skipped.add(i + 1);
                log.warn("Skipping invalid source entry at {}:{}: {}", file, i + 1, reason);
            }
        }
        if (dataLines > 0 && out.isEmpty()) {
            throw new IOException("sources file " + file
                    + " has " + dataLines + " data line(s) but none are valid");
        }
        return new SearchRootsSnapshot(
                Collections.unmodifiableList(out),
                Collections.unmodifiableList(skipped));
    }
}
