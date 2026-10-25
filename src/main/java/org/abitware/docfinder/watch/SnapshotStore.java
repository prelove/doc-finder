package org.abitware.docfinder.watch;

import org.abitware.docfinder.util.AppPaths;
import org.abitware.docfinder.util.Utils;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Disk-backed sorted snapshots. Production readers retain one record, writers one fixed chunk. */
public class SnapshotStore {
    static final int CHUNK_SIZE = 4096;
    private static final String HEADER = "#docfinder-snapshot-v2";
    private final Path dir;

    public SnapshotStore() { this(AppPaths.getBaseDir().resolve("snapshots")); }
    SnapshotStore(Path dir) { this.dir = dir; }

    private String keyForRoot(Path root) {
        String raw = root.toAbsolutePath().toString();
        String norm = Utils.isWindows() ? Utils.canonicalizeWinPathString(raw) : raw;
        String key = norm.replace(':', '_').replace('\\', '_').replace('/', '_');
        if (key.isEmpty()) key = "root";
        return key + "-" + Integer.toHexString(norm.hashCode());
    }

    private Path baseline(Path root) { return dir.resolve(keyForRoot(root) + ".txt"); }

    /** Compatibility API; the polling pipeline uses cursors instead of loading maps. */
    public Map<String, Entry> load(Path root) {
        Map<String, Entry> values = new HashMap<>();
        try (Cursor cursor = open(root)) {
            Record record;
            while ((record = cursor.next()) != null) values.put(record.path, record.entry);
            return values;
        } catch (IOException e) { return null; }
    }

    public boolean save(Path root, Map<String, Entry> values) {
        try (Builder builder = newBuilder(); Staged staged = writeMap(builder, values)) {
            staged.publish(root);
            return true;
        } catch (IOException e) { return false; }
    }

    private Staged writeMap(Builder builder, Map<String, Entry> values) throws IOException {
        for (Map.Entry<String, Entry> entry : values.entrySet()) builder.add(entry.getKey(), entry.getValue());
        return builder.finish();
    }

    Builder newBuilder() throws IOException {
        Files.createDirectories(dir);
        return new Builder();
    }

    /** Legacy unordered snapshots are normalized by the same bounded external sorter. */
    Cursor open(Path root) throws IOException {
        Path file = baseline(root);
        if (!Files.exists(file)) return new Cursor(null, null);
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            if (HEADER.equals(reader.readLine())) return new Cursor(file, null);
        }
        try (Builder builder = newBuilder(); BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                int last = line.lastIndexOf('|');
                int previous = last < 0 ? -1 : line.lastIndexOf('|', last - 1);
                if (previous < 0) throw new IOException("Invalid legacy snapshot record");
                builder.add(line.substring(0, previous), new Entry(number(line.substring(previous + 1, last)), number(line.substring(last + 1))));
            }
            Staged staged = builder.finish();
            return new Cursor(staged.file, staged);
        }
    }

    private static long number(String value) throws IOException {
        try { return Long.parseLong(value); }
        catch (NumberFormatException e) { throw new IOException("Invalid snapshot number", e); }
    }

    static final class Record {
        final String path;
        final Entry entry;
        Record(String path, Entry entry) { this.path = path; this.entry = entry; }
    }

    static final class Cursor implements AutoCloseable {
        private final BufferedReader reader;
        private final Staged owned;
        private String previousPath;
        Cursor(Path file, Staged owned) throws IOException {
            this.owned = owned;
            this.reader = file == null ? null : Files.newBufferedReader(file, StandardCharsets.UTF_8);
            if (reader != null && !HEADER.equals(reader.readLine())) {
                reader.close();
                throw new IOException("Invalid sorted snapshot header");
            }
        }
        Record next() throws IOException {
            if (reader == null) return null;
            String line = reader.readLine();
            if (line == null) return null;
            String[] fields = line.split("\\|", -1);
            if (fields.length != 4) throw new IOException("Invalid snapshot record");
            try {
                String path = new String(Base64.getDecoder().decode(fields[0]), StandardCharsets.UTF_8);
                if (previousPath != null && previousPath.compareTo(path) >= 0) throw new IOException("Unsorted snapshot record");
                if (!"d".equals(fields[3]) && !"f".equals(fields[3])) throw new IOException("Invalid snapshot kind");
                previousPath = path;
                return new Record(path, new Entry(number(fields[1]), number(fields[2]), "d".equals(fields[3])));
            } catch (IllegalArgumentException e) { throw new IOException("Invalid encoded snapshot path", e); }
        }
        @Override public void close() throws IOException {
            try { if (reader != null) reader.close(); }
            finally { if (owned != null) owned.close(); }
        }
    }

    final class Staged implements AutoCloseable {
        private Path file;
        Staged(Path file) { this.file = file; }
        Cursor cursor() throws IOException { return new Cursor(file, null); }
        Cursor ownedCursor() throws IOException { return new Cursor(file, this); }
        void publish(Path root) throws IOException {
            Files.move(file, baseline(root), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            file = null;
        }
        @Override public void close() throws IOException { if (file != null) Files.deleteIfExists(file); }
    }

    final class Builder implements AutoCloseable {
        private final List<Record> chunk = new ArrayList<>(CHUNK_SIZE);
        // Binary carry merging keeps only O(log N) run filenames and opens two runs at a time.
        private final List<Path> levels = new ArrayList<>();
        private final Set<Path> owned = new HashSet<>();
        private boolean finished;

        void add(String path, Entry entry) throws IOException {
            checkInterrupted();
            if (finished) throw new IOException("Snapshot builder finished");
            chunk.add(new Record(path, entry));
            if (chunk.size() == CHUNK_SIZE) flush();
        }

        private Path temporary() throws IOException {
            Path file = Files.createTempFile(dir, "snapshot-", ".tmp");
            owned.add(file);
            return file;
        }

        private void flush() throws IOException {
            if (chunk.isEmpty()) return;
            chunk.sort(Comparator.comparing(record -> record.path));
            Path run = temporary();
            try (BufferedWriter writer = Files.newBufferedWriter(run, StandardCharsets.UTF_8)) {
                writer.write(HEADER); writer.newLine();
                for (int i = 0; i < chunk.size(); i++) {
                    if (i + 1 == chunk.size() || !chunk.get(i).path.equals(chunk.get(i + 1).path)) write(writer, chunk.get(i));
                }
            }
            chunk.clear();
            int level = 0;
            while (level < levels.size() && levels.get(level) != null) {
                run = merge(levels.get(level), run);
                levels.set(level++, null);
            }
            if (level == levels.size()) levels.add(run); else levels.set(level, run);
        }

        private Path merge(Path left, Path right) throws IOException {
            Path result = temporary();
            try (Cursor a = new Cursor(left, null); Cursor b = new Cursor(right, null);
                 BufferedWriter writer = Files.newBufferedWriter(result, StandardCharsets.UTF_8)) {
                writer.write(HEADER); writer.newLine();
                Record x = a.next(), y = b.next();
                while (x != null || y != null) {
                    checkInterrupted();
                    int order = x == null ? 1 : y == null ? -1 : x.path.compareTo(y.path);
                    if (order < 0) { write(writer, x); x = a.next(); }
                    else if (order > 0) { write(writer, y); y = b.next(); }
                    else { write(writer, y); x = a.next(); y = b.next(); }
                }
            }
            Files.delete(left); owned.remove(left);
            Files.delete(right); owned.remove(right);
            return result;
        }

        Staged finish() throws IOException {
            flush();
            Path result = null;
            for (int i = levels.size() - 1; i >= 0; i--) {
                Path level = levels.get(i);
                if (level != null) result = result == null ? level : merge(result, level);
            }
            if (result == null) {
                result = temporary();
                Files.write(result, Collections.singletonList(HEADER), StandardCharsets.UTF_8);
            }
            owned.remove(result);
            finished = true;
            return new Staged(result);
        }

        @Override public void close() throws IOException {
            IOException failure = null;
            for (Path file : owned) try { Files.deleteIfExists(file); } catch (IOException e) { failure = e; }
            owned.clear(); chunk.clear();
            if (failure != null) throw failure;
        }
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Snapshot cancelled");
    }

    private static void write(BufferedWriter writer, Record record) throws IOException {
        writer.write(Base64.getEncoder().encodeToString(record.path.getBytes(StandardCharsets.UTF_8)));
        writer.write('|'); writer.write(Long.toString(record.entry.size));
        writer.write('|'); writer.write(Long.toString(record.entry.mtime));
        writer.write('|'); writer.write(record.entry.directory ? "d" : "f"); writer.newLine();
    }

    public static class Entry {
        public final long size;
        public final long mtime;
        public final boolean directory;
        public Entry(long size, long mtime) { this(size, mtime, false); }
        public Entry(long size, long mtime, boolean directory) { this.size = size; this.mtime = mtime; this.directory = directory; }
    }
}
