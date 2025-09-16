package org.abitware.docfinder.index;

import org.abitware.docfinder.util.Utils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

/** 管理“索引源”列表（带本地/网络标记）。文件格式：每行 path|0/1 （1=network） */
public class SourceManager {
    private final Path baseDir = Paths.get(System.getProperty("user.home"), ".docfinder");
    private final Path sourcesFile = baseDir.resolve("sources.txt"); // 向后兼容：无 | 时按旧格式读取
    private final Path indexDir = baseDir.resolve("index");

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
}
