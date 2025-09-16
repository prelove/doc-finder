package org.abitware.docfinder.watch;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** 每个根目录保存一份快照：path|size|mtime */
public class SnapshotStore {
    private final Path dir;

    public SnapshotStore() {
        this.dir = Paths.get(System.getProperty("user.home"), ".docfinder", "snapshots");
    }

    private String keyForRoot(Path root) {
        String raw = root.toAbsolutePath().toString();
        String norm = org.abitware.docfinder.util.Utils.isWindows()
                ? org.abitware.docfinder.util.Utils.canonicalizeWinPathString(raw)
                : raw;
        String k = norm.replace(':','_').replace('\\','_').replace('/','_');
        return k.isEmpty() ? "root" : k;
    }

    public Map<String, Entry> load(Path root) {
        Map<String, Entry> map = new HashMap<>();
        try {
            Files.createDirectories(dir);
            Path f = dir.resolve(keyForRoot(root) + ".txt");
            if (!Files.exists(f)) return map;
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] sp = line.split("\\|", -1);
                if (sp.length < 3) continue;
                String path = sp[0];
                long size = parseLong(sp[1]);
                long mtime = parseLong(sp[2]);
                map.put(path, new Entry(size, mtime));
            }
        } catch (IOException ignore) {}
        return map;
    }

    public void save(Path root, Map<String, Entry> map) {
        try {
            Files.createDirectories(dir);
            Path f = dir.resolve(keyForRoot(root) + ".txt");
            List<String> lines = new ArrayList<>();
            for (Map.Entry<String, Entry> e : map.entrySet()) {
                lines.add(e.getKey() + "|" + e.getValue().size + "|" + e.getValue().mtime);
            }
            Files.write(f, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException ignore) {}
    }

    private static long parseLong(String s) {
        try { return Long.parseLong(s); } catch (Exception e) { return 0L; }
    }

    public static class Entry {
        public final long size;
        public final long mtime;
        public Entry(long size, long mtime) { this.size = size; this.mtime = mtime; }
    }
}
