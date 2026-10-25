package org.abitware.docfinder.index;

import org.abitware.docfinder.util.AppPaths;
import org.abitware.docfinder.util.PathScope;
import org.apache.lucene.analysis.core.KeywordAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TermRangeQuery;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * App-wide recovery registry. All paths remain on disk until explicitly removed
 * after successful re-indexing. Only snapshots and the writer's RAM buffer are
 * bounded; age and snapshot capacity never discard recovery evidence.
 */
public final class Tombstones {
    private static final Logger LOG = LoggerFactory.getLogger(Tombstones.class);
    private static final int MAX = 4096;
    private static final long FLUSH_DELAY_MS = 2_000;
    private static final String PATH = "path";
    private static final String SCOPE = "scope";
    private static final String MIGRATED = "legacy_migrated";
    private static final Sort PATH_SORT = new Sort(new SortField(PATH, SortField.Type.STRING));
    private static final Object LOCK = new Object();
    private static final AtomicBoolean flushRequested = new AtomicBoolean();
    private static final ScheduledExecutorService flusher = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "docfinder-tombstone-flush");
        thread.setDaemon(true);
        return thread;
    });
    private static Store store;

    static {
        // A fixed-delay retry cannot miss changes arriving as a previous flush
        // finishes, and failed commits remain dirty until a later commit succeeds.
        flusher.scheduleWithFixedDelay(Tombstones::flushInBackground,
                FLUSH_DELAY_MS, FLUSH_DELAY_MS, TimeUnit.MILLISECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                close();
            } catch (RuntimeException e) {
                LOG.error("Could not close recovery registry", e);
            }
        }, "docfinder-tombstone-close"));
    }

    private Tombstones() {
    }

    /** Records an index-deleted path. I/O failures are reported to the caller. */
    public static void record(String path) {
        if (path == null || path.isEmpty()) {
            return;
        }
        synchronized (LOCK) {
            try {
                currentStore().record(path);
            } catch (IOException e) {
                throw failure(e);
            }
        }
    }

    /** Applies a batch in memory; call flush once after the complete index commit batch. */
    public static void recordBatch(Collection<String> paths) {
        if (paths != null) {
            for (String path : paths) {
                record(path);
            }
        }
    }

    /** Removes evidence only after the caller has successfully restored the index document. */
    public static void remove(String path) {
        if (path == null || path.isEmpty()) {
            return;
        }
        synchronized (LOCK) {
            try {
                Store current = currentStore();
                current.writer.deleteDocuments(new Term(PATH, path));
                current.dirty = true;
            } catch (IOException e) {
                throw failure(e);
            }
        }
    }

    /** Applies a batch in memory; the caller retains it for retry until flush succeeds. */
    public static void removeBatch(Collection<String> paths) {
        if (paths != null) {
            for (String path : paths) {
                remove(path);
            }
        }
    }

    public static boolean contains(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        synchronized (LOCK) {
            try (DirectoryReader reader = DirectoryReader.open(currentStore().writer)) {
                return new IndexSearcher(reader).count(new TermQuery(new Term(PATH, path))) != 0;
            } catch (IOException e) {
                throw failure(e);
            }
        }
    }

    /** Compatibility snapshot; at most 4096 paths, ordered by path. */
    public static List<String> snapshot() {
        return snapshotPage(null, MAX, null);
    }

    /**
     * A path-ordered page strictly after {@code afterKey}, within the supplied
     * source roots. Empty roots, nonpositive limits, and the tail return an empty
     * page. Limits above 4096 are capped. The caller owns cursor wraparound.
     */
    public static List<String> snapshotAfter(String afterKey, int limit, List<Path> roots) {
        if (roots == null || roots.isEmpty() || limit <= 0) {
            return Collections.emptyList();
        }
        return snapshotPage(afterKey, Math.min(limit, MAX), roots);
    }

    public static boolean isEmpty() {
        synchronized (LOCK) {
            try (DirectoryReader reader = DirectoryReader.open(currentStore().writer)) {
                return reader.numDocs() == 0;
            } catch (IOException e) {
                throw failure(e);
            }
        }
    }

    /** Synchronous durable commit. Call on a background thread, never on the EDT. */
    public static void flush() {
        synchronized (LOCK) {
            if (store != null) {
                try {
                    store.flush();
                } catch (IOException e) {
                    throw failure(e);
                }
            }
        }
    }

    /** Requests a background commit without waiting for the storage lock or I/O. */
    public static void requestFlush() {
        if (flushRequested.compareAndSet(false, true)) {
            flusher.execute(() -> {
                try {
                    flushInBackground();
                } finally {
                    flushRequested.set(false);
                }
            });
        }
    }

    /** Commits and releases the app store. Call on a background shutdown thread. */
    public static void close() {
        synchronized (LOCK) {
            if (store != null) {
                try {
                    store.close();
                    store = null;
                } catch (IOException e) {
                    throw failure(e);
                }
            }
        }
    }

    private static void flushInBackground() {
        try {
            flush();
        } catch (RuntimeException e) {
            LOG.warn("Recovery registry commit failed; retrying in {} ms", FLUSH_DELAY_MS, e);
        }
    }

    private static List<String> snapshotPage(String afterKey, int limit, List<Path> roots) {
        synchronized (LOCK) {
            try (DirectoryReader reader = DirectoryReader.open(currentStore().writer)) {
                BooleanQuery.Builder query = new BooleanQuery.Builder();
                query.add(afterKey == null ? new MatchAllDocsQuery()
                        : new TermRangeQuery(PATH, new BytesRef(afterKey), null, false, false),
                        BooleanClause.Occur.FILTER);
                if (roots != null) {
                    BooleanQuery.Builder scope = new BooleanQuery.Builder();
                    for (Path root : roots) {
                        if (root == null) {
                            continue;
                        }
                        String normalized = PathScope.normalizeIndexedPath(root);
                        BooleanQuery.Builder oneRoot = new BooleanQuery.Builder();
                        oneRoot.add(new TermQuery(new Term(SCOPE, normalized)), BooleanClause.Occur.SHOULD);
                        oneRoot.add(new PrefixQuery(new Term(SCOPE, PathScope.descendantPrefix(normalized))),
                                BooleanClause.Occur.SHOULD);
                        scope.add(oneRoot.build(), BooleanClause.Occur.SHOULD);
                    }
                    query.add(scope.build(), BooleanClause.Occur.FILTER);
                }
                IndexSearcher searcher = new IndexSearcher(reader);
                List<String> paths = new ArrayList<>();
                for (ScoreDoc hit : searcher.search(query.build(), limit, PATH_SORT).scoreDocs) {
                    paths.add(searcher.doc(hit.doc).get(PATH));
                }
                return paths;
            } catch (IOException e) {
                throw failure(e);
            }
        }
    }

    private static Store currentStore() throws IOException {
        Path base = AppPaths.getBaseDir();
        if (store != null && !store.base.equals(base)) {
            store.close();
            store = null;
        }
        if (store == null) {
            store = new Store(base);
        }
        return store;
    }

    private static UncheckedIOException failure(IOException e) {
        return new UncheckedIOException("Recovery registry I/O failed; operation must be retried", e);
    }

    private static final class Store implements AutoCloseable {
        private final Path base;
        private final Directory directory;
        private final IndexWriter writer;
        private boolean dirty;

        private Store(Path base) throws IOException {
            this.base = base;
            Files.createDirectories(base);
            directory = FSDirectory.open(base.resolve("deleted-paths-index"));
            IndexWriter openedWriter = null;
            try {
                IndexWriterConfig config = new IndexWriterConfig(new KeywordAnalyzer());
                config.setRAMBufferSizeMB(8);
                openedWriter = new IndexWriter(directory, config);
                writer = openedWriter;
                migrateLegacy();
            } catch (IOException | RuntimeException e) {
                if (openedWriter != null) {
                    try {
                        openedWriter.rollback();
                    } catch (IOException cleanup) {
                        e.addSuppressed(cleanup);
                    }
                }
                try {
                    directory.close();
                } catch (IOException cleanup) {
                    e.addSuppressed(cleanup);
                }
                throw e;
            }
        }

        private void record(String path) throws IOException {
            Document document = new Document();
            document.add(new StringField(PATH, path, org.apache.lucene.document.Field.Store.YES));
            document.add(new SortedDocValuesField(PATH, new BytesRef(path)));
            document.add(new StringField(SCOPE, PathScope.normalizeIndexedPath(Paths.get(path)),
                    org.apache.lucene.document.Field.Store.NO));
            writer.updateDocument(new Term(PATH, path), document);
            dirty = true;
        }

        private void migrateLegacy() throws IOException {
            Path legacy = base.resolve("deleted-paths.txt");
            if (!Files.exists(legacy)) {
                return;
            }
            boolean migrated = false;
            for (Map.Entry<String, String> entry : writer.getLiveCommitData()) {
                if (MIGRATED.equals(entry.getKey()) && "true".equals(entry.getValue())) {
                    migrated = true;
                }
            }
            if (!migrated) {
                try (BufferedReader reader = Files.newBufferedReader(legacy, StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        int separator = line.lastIndexOf('|');
                        if (separator > 0) {
                            // Timestamps only guided the old scheduler. No age
                            // expires a path that has not yet been restored.
                            record(line.substring(0, separator));
                        }
                    }
                }
                writer.setLiveCommitData(Collections.singletonMap(MIGRATED, "true").entrySet());
                dirty = true;
                flush();
            }
            // The committed marker prevents a failed rename from importing old
            // records again after they have since been successfully removed.
            Files.move(legacy, legacy.resolveSibling("deleted-paths.txt.bak"),
                    StandardCopyOption.REPLACE_EXISTING);
        }

        private void flush() throws IOException {
            if (dirty) {
                writer.commit();
                dirty = false;
            }
        }

        @Override
        public void close() throws IOException {
            // A failed commit keeps this store open and dirty for retry.
            flush();
            try {
                writer.close();
            } finally {
                directory.close();
            }
        }
    }
}
