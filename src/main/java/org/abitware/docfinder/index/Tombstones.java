package org.abitware.docfinder.index;

import org.abitware.docfinder.util.AppPaths;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bounded registry of paths whose index documents were deleted — a
 * "tombstone" list persisted at {@code ~/.docfinder/deleted-paths.txt}.
 * Every deletePath() call records an entry, so a file that later comes back
 * (cut away and restored, share remounted, restored from recycle bin) can be
 * resurrected by re-indexing instead of staying permanently invisible even
 * when no watcher or poller covers its root.
 *
 * Bounds: at most MAX entries (oldest evicted), entries expire after
 * TTL_MS, file writes are debounced on a single daemon task.
 */
public final class Tombstones {

    private static final int MAX = 4096;
    private static final long TTL_MS = 30L * 24 * 3600 * 1000;
    private static final long FLUSH_DELAY_MS = 2_000;

    private static final Object LOCK = new Object();
    private static final LinkedHashMap<String, Long> map = new LinkedHashMap<>();
    private static boolean loaded;
    private static boolean dirty;
    private static Thread flusher;

    private Tombstones() {
    }

    private static Path file() {
        return AppPaths.getBaseDir().resolve("deleted-paths.txt");
    }

    /** Records {@code path} (normalized index form) as index-deleted now. */
    public static void record(String path) {
        if (path == null || path.isEmpty()) {
            return;
        }
        synchronized (LOCK) {
            ensureLoaded();
            map.remove(path);
            map.put(path, System.currentTimeMillis());
            while (map.size() > MAX) {
                java.util.Iterator<String> it = map.keySet().iterator();
                it.next();
                it.remove();
            }
            dirty = true;
            scheduleFlushLocked();
        }
    }

    /** Clears a tombstone — called once the path is back in the index. */
    public static void remove(String path) {
        if (path == null) {
            return;
        }
        synchronized (LOCK) {
            ensureLoaded();
            if (map.remove(path) != null) {
                dirty = true;
                scheduleFlushLocked();
            }
        }
    }

    /** Oldest-first snapshot of current tombstone paths (bounded copy). */
    public static List<String> snapshot() {
        synchronized (LOCK) {
            ensureLoaded();
            return new ArrayList<>(map.keySet());
        }
    }

    public static boolean isEmpty() {
        synchronized (LOCK) {
            ensureLoaded();
            return map.isEmpty();
        }
    }

    /** Writes the file now if dirty — called from service close paths. */
    public static void flush() {
        synchronized (LOCK) {
            flushLocked();
        }
    }

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        Path f = file();
        long now = System.currentTimeMillis();
        try {
            if (!Files.exists(f)) {
                return;
            }
            try (BufferedReader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                String line;
                while ((line = r.readLine()) != null) {
                    int sep = line.lastIndexOf('|');
                    if (sep <= 0) {
                        continue;
                    }
                    long ts;
                    try {
                        ts = Long.parseLong(line.substring(sep + 1).trim());
                    } catch (NumberFormatException e) {
                        continue;
                    }
                    if (now - ts > TTL_MS) {
                        continue; // expired tombstones are dropped on load
                    }
                    map.put(line.substring(0, sep), ts);
                }
            }
            while (map.size() > MAX) {
                java.util.Iterator<String> it = map.keySet().iterator();
                it.next();
                it.remove();
            }
        } catch (IOException ignore) {
            // Unreadable tombstone file — start empty rather than guessing.
        }
    }

    private static void scheduleFlushLocked() {
        if (flusher != null && flusher.isAlive()) {
            return;
        }
        flusher = new Thread(() -> {
            try {
                Thread.sleep(FLUSH_DELAY_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
            synchronized (LOCK) {
                flushLocked();
            }
        }, "docfinder-tombstone-flush");
        flusher.setDaemon(true);
        flusher.start();
    }

    private static void flushLocked() {
        if (!dirty) {
            return;
        }
        dirty = false;
        Path f = file();
        Path tmp = f.resolveSibling("deleted-paths.tmp");
        try {
            Files.createDirectories(f.getParent());
            try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                for (Map.Entry<String, Long> e : map.entrySet()) {
                    w.write(e.getKey());
                    w.write('|');
                    w.write(Long.toString(e.getValue()));
                    w.newLine();
                }
            }
            try {
                Files.move(tmp, f, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException am) {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ignore) {
            dirty = true; // keep dirty so a later flush retries
        }
    }
}
