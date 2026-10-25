package org.abitware.docfinder.watch;

import org.abitware.docfinder.index.IndexSettings;
import org.abitware.docfinder.index.LuceneIndexer;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.*;

class LiveRecoveryTest {
    @TempDir Path temp;

    @Test
    void shouldRetryBatchAfterWriterOpenFails() throws Exception {
        Path source = Files.createDirectory(temp.resolve("source"));
        Path file = Files.write(source.resolve("new.txt"), new byte[] { 1 });
        Path index = temp.resolve("index");
        IndexSettings settings = new IndexSettings();
        try (LiveIndexService live = new LiveIndexService(index, settings, Collections.singletonList(source))) {
            try (LuceneIndexer blocker = new LuceneIndexer(index, settings)) {
                blocker.commit();
                live.onChange("CREATE", file);
                flush(live);
                awaitDrained(live);
            }
            flush(live);
            awaitDrained(live);
            assertEquals(1, count(index), "a transient failed batch must remain pending until a successful retry");
        }
    }

    @Test
    void shouldReconcileLostDeleteAgainstActualIndexWithoutPriorSnapshot() throws Exception {
        Path source = Files.createDirectory(temp.resolve("source"));
        Path gone = Files.write(source.resolve("gone.txt"), new byte[] { 1 });
        Files.write(source.resolve("kept.txt"), new byte[] { 2 });
        Path index = temp.resolve("index");
        IndexSettings settings = new IndexSettings();
        try (LuceneIndexer indexer = new LuceneIndexer(index, settings)) { indexer.indexFolder(source); }
        Files.delete(gone);
        try (LiveIndexService live = new LiveIndexService(index, settings, Collections.singletonList(source))) {
            live.onOverflow(source);
            flush(live);
            awaitDrained(live);
            assertEquals(2, count(index), "overflow reconciliation must apply lost deletes, including before a snapshot exists");
            assertTrue(live.pendingReconcileRoots().isEmpty());
        }
    }

    @Test
    void shouldReconcileChangesMadeBeforeWatchStarts() throws Exception {
        Path source = Files.createDirectory(temp.resolve("startup-source"));
        Path gone = Files.write(source.resolve("gone.txt"), new byte[] { 1 });
        Path index = temp.resolve("startup-index");
        IndexSettings settings = metadataOnlySettings();
        try (LuceneIndexer writer = new LuceneIndexer(index, settings)) { writer.indexFolder(source); }
        Files.delete(gone);
        Path added = Files.write(source.resolve("added.txt"), new byte[] { 2 });
        CountDownLatch committed = new CountDownLatch(1);
        try (LiveIndexService live = new LiveIndexService(index, settings, Collections.singletonList(source), committed::countDown)) {
            live.start();
            assertTrue(committed.await(30, TimeUnit.SECONDS), "starting watch must reconcile changes missed while stopped");
            assertFalse(hasPath(index, gone));
            assertTrue(hasPath(index, added));
        }
    }

    @Test
    void shouldPreserveRestoredUpsertAgainstQueuedDeleteAndRetrySafely() throws Exception {
        Path source = Files.createDirectory(temp.resolve("delete-source"));
        Path file = Files.write(source.resolve("restored.txt"), new byte[] { 1 });
        Path index = temp.resolve("delete-index");
        IndexSettings settings = metadataOnlySettings();
        try (LuceneIndexer writer = new LuceneIndexer(index, settings)) { writer.indexFolder(source); }
        Files.delete(file);
        try (LiveIndexService live = new LiveIndexService(index, settings, Collections.singletonList(source))) {
            org.abitware.docfinder.index.IndexWriteCoordinator.call(() -> {
                live.onChange("DELETE", file);
                // Isolate the queued file-event protocol from the separate
                // full-root reconciliation fallback.
                Field reconcileField = LiveIndexService.class.getDeclaredField("reconcileRoots");
                reconcileField.setAccessible(true);
                ((java.util.Set<?>) reconcileField.get(live)).clear();
                flush(live);
                Field lockField = org.abitware.docfinder.index.IndexWriteCoordinator.class.getDeclaredField("WRITE_LOCK");
                lockField.setAccessible(true);
                java.util.concurrent.locks.ReentrantLock lock = (java.util.concurrent.locks.ReentrantLock) lockField.get(null);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                while (!lock.hasQueuedThreads() && System.nanoTime() < deadline) Thread.sleep(10);
                assertTrue(lock.hasQueuedThreads(), "the old deletion/reconcile must reach the held writer");
                Files.write(file, new byte[] { 2 });
                try (LuceneIndexer writer = new LuceneIndexer(index, settings)) { writer.upsertFile(file); writer.commit(); }
                return null;
            });
            awaitDrained(live);
            assertTrue(hasPath(index, file), "a queued deletion must preserve a newer restored document");
            flush(live);
            awaitDrained(live);
            assertTrue(hasPath(index, file), "retry must re-check the restored path rather than replay the stale DELETE");
        }
    }

    @Test
    void shouldIndexAlreadyPopulatedDirectoryMovedIntoWatchedRoot() throws Exception {
        Path source = Files.createDirectory(temp.resolve("destination"));
        Path staging = Files.createDirectory(temp.resolve("staging"));
        Files.write(staging.resolve("existing.txt"), new byte[] { 3 });
        Path index = temp.resolve("move-index");
        IndexSettings settings = metadataOnlySettings();
        try (LuceneIndexer writer = new LuceneIndexer(index, settings)) { writer.indexFolder(source); }
        CountDownLatch startup = new CountDownLatch(1);
        try (LiveIndexService live = new LiveIndexService(index, settings, Collections.singletonList(source), startup::countDown)) {
            live.start();
            assertTrue(startup.await(30, TimeUnit.SECONDS));
            Path moved = Files.move(staging, source.resolve("moved"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (!hasPath(index, moved.resolve("existing.txt")) && System.nanoTime() < deadline) Thread.sleep(30);
            assertTrue(hasPath(index, moved.resolve("existing.txt")),
                    "directory registration must reconcile files created before their watch keys existed");
        }
    }

    private static IndexSettings metadataOnlySettings() {
        IndexSettings settings = new IndexSettings();
        settings.includeExt = Collections.emptyList();
        settings.parseTextLike = false;
        return settings;
    }

    private static boolean hasPath(Path index, Path path) throws Exception {
        try (FSDirectory directory = FSDirectory.open(index); DirectoryReader reader = DirectoryReader.open(directory)) {
            return new org.apache.lucene.search.IndexSearcher(reader).count(new org.apache.lucene.search.TermQuery(
                    new org.apache.lucene.index.Term("path", org.abitware.docfinder.util.Utils.normalizeForIndex(path)))) != 0;
        }
    }

    private static int count(Path index) throws Exception {
        try (FSDirectory directory = FSDirectory.open(index); DirectoryReader reader = DirectoryReader.open(directory)) {
            return reader.numDocs();
        }
    }
    private static void flush(LiveIndexService service) throws Exception {
        Method flush = LiveIndexService.class.getDeclaredMethod("flushPending");
        flush.setAccessible(true); flush.invoke(service);
    }
    private static void awaitDrained(LiveIndexService service) throws Exception {
        Field active = LiveIndexService.class.getDeclaredField("drainActive");
        active.setAccessible(true);
        AtomicBoolean flag = (AtomicBoolean) active.get(service);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (flag.get() && System.nanoTime() < deadline) Thread.sleep(20);
        assertFalse(flag.get(), "live drain must finish within the bounded retry interval");
    }
}
