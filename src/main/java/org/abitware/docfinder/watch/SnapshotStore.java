package org.abitware.docfinder.watch;

import org.abitware.docfinder.util.AppPaths;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * 每个根目录保存一份快照：path|size|mtime。
 * Reads and writes are streamed — no full-line copies — and saves go through
 * a tmp file + atomic move so a partial write can never clobber the last
 * good baseline. {@link #load} returns null on I/O failure so the caller can
 * distinguish "no baseline yet" (empty map) from "baseline unreadable"
 * (null → skip the diff, never delete against a broken baseline).
 */
public class SnapshotStore {
    private final Path dir;

    public SnapshotStore() {
        this.dir = AppPaths.getBaseDir().resolve("snapshots");
    }

    private String keyForRoot(Path root) {
        String raw = root.toAbsolutePath().toString();
        String norm = org.abitware.docfinder.util.Utils.isWindows()
                ? org.abitware.docfinder.util.Utils.canonicalizeWinPathString(raw)
                : raw;
        String k = norm.replace(':','_').replace('\\','_').replace('/','_');
        if (k.isEmpty()) k = "root";
        // Separator-folding alone collides (E:\a\b vs E:\a_b); the hash
        // suffix disambiguates so two roots can never share a tmp/file.
        return k + "-" + Integer.toHexString(norm.hashCode());
    }

    /**
     * Loads the stored baseline for {@code root}. Returns an empty map when
     * no baseline exists yet, or {@code null} when one exists but cannot be
     * read — callers must not diff against an unreadable baseline.
     */
    public Map<String, Entry> load(Path root) {
        Map<String, Entry> map = new HashMap<>();
        try {
            Files.createDirectories(dir);
            Path f = dir.resolve(keyForRoot(root) + ".txt");
            if (!Files.exists(f)) return map;
            try (BufferedReader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    String[] sp = line.split("\\|", -1);
                    if (sp.length < 3) continue;
                    map.put(sp[0], new Entry(parseLong(sp[1]), parseLong(sp[2])));
                }
            }
        } catch (IOException e) {
            return null; // unreadable baseline — NOT the same as an empty one
        }
        return map;
    }

    /**
     * Persists {@code map} as the new baseline for {@code root} via a tmp
     * file + atomic move, so an incomplete write never replaces the last
     * good snapshot. Returns false when the baseline could not be stored —
     * the old baseline stays in place and the next poll re-diffs it.
     */
    public boolean save(Path root, Map<String, Entry> map) {
        try {
            Files.createDirectories(dir);
            String key = keyForRoot(root);
            Path f = dir.resolve(key + ".txt");
            Path tmp = dir.resolve(key + ".tmp");
            try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                for (Map.Entry<String, Entry> e : map.entrySet()) {
                    w.write(e.getKey());
                    w.write('|');
                    w.write(Long.toString(e.getValue().size));
                    w.write('|');
                    w.write(Long.toString(e.getValue().mtime));
                    w.newLine();
                }
            }
            try {
                Files.move(tmp, f, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException am) {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException e) {
            // Never leave a half-written tmp behind — a later load() must not
            // trip over residue, and the stale tmp is useless anyway.
            try {
                Files.deleteIfExists(dir.resolve(keyForRoot(root) + ".tmp"));
            } catch (IOException ignore) {
            }
            return false;
        }
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
