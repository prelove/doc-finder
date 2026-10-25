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
