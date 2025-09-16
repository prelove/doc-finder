package org.abitware.docfinder.search;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class SearchHistoryManager {
    private static final int MAX = 100;
    private final Path file;

    public SearchHistoryManager() {
        this.file = Paths.get(System.getProperty("user.home"), ".docfinder", "history.txt");
    }

    /** 读取历史（最新在前） */
    public java.util.List<String> load() {
        java.util.List<String> out = new ArrayList<>();
        try {
            if (Files.exists(file)) {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String s = line.trim();
                    if (!s.isEmpty() && !out.contains(s)) out.add(s);
                }
            }
        } catch (IOException ignore) {}
        return out;
    }

    /** 将 q 插入历史（去重置顶并保存），返回最新列表 */
    public java.util.List<String> addAndSave(String q) {
        q = (q == null) ? "" : q.trim();
        if (q.isEmpty()) return load();
        java.util.List<String> list = load();
        list.remove(q);
        list.add(0, q);
        while (list.size() > MAX) list.remove(list.size() - 1);
        save(list);
        return list;
    }

    public void save(java.util.List<String> list) {
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, list, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException ignore) {}
    }
}
