package org.abitware.docfinder.watch;

import org.abitware.docfinder.index.IndexSettings;
import org.abitware.docfinder.index.IndexWriteCoordinator;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.abitware.docfinder.index.LuceneIndexer;

import static org.junit.jupiter.api.Assertions.*;

class PollPipelineRecoveryTest {
    @TempDir Path temp;

    @Test
    void shouldNotPublishUnappliedSnapshotAfterStopWhileWaitingForWriter() throws Exception {
        Path source = Files.createDirectory(temp.resolve("source"));
        Files.write(source.resolve("one.txt"), new byte[] { 1 });
        NetPollerService poller = new NetPollerService(temp.resolve("index"), new IndexSettings(), Collections.singletonList(source));
        try {
            IndexWriteCoordinator.run(() -> {
                poller.pollNowAsync();
                awaitWorkersWaiting(poller, 1);
                poller.stop();
            });
            awaitNoPending(poller);
            assertTrue(new SnapshotStore().load(source).isEmpty(), "stop must not advance a baseline whose diff never ran");
            assertFalse(Files.exists(temp.resolve("index")), "a stopped blocked writer must never open the index");
        } finally {
            poller.stop();
        }
    }

    @Test
    void shouldDrainCancelledQueuedRootsAndAllowRestart() throws Exception {
        List<Path> roots = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Path root = Files.createDirectory(temp.resolve("source" + i));
            Files.write(root.resolve("one.txt"), new byte[] { 1 });
            roots.add(root);
        }
        NetPollerService poller = new NetPollerService(temp.resolve("index"), new IndexSettings(), roots);
        try {
            IndexWriteCoordinator.run(() -> {
                poller.pollNowAsync();
                awaitWorkersWaiting(poller, 5);
                poller.stop();
            });
            awaitNoPending(poller);
            poller.start(60);
            NetPollerService.PollStats stats = poller.pollNowAsync().get(30, TimeUnit.SECONDS);
            assertEquals(5, stats.scannedFiles, "queued cancellation must release the round gate for a later start");
        } finally {
            poller.stop();
        }
    }

    @Test
    void shouldRoundTripDelimiterAndNewlineInSnapshotKeys() {
        SnapshotStore store = new SnapshotStore();
        Map<String, SnapshotStore.Entry> values = new HashMap<>();
        values.put("/source/a|b\nc.txt", new SnapshotStore.Entry(17, 23));
        assertTrue(store.save(temp, values));
        Map<String, SnapshotStore.Entry> loaded = store.load(temp);
        assertNotNull(loaded.get("/source/a|b\nc.txt"), "snapshot records must encode paths unambiguously");
        assertEquals(17, loaded.get("/source/a|b\nc.txt").size);
    }

    @Test
    void shouldRetainBaselineOnWriterFailureAndRetryRealDirectoryDiff() throws Exception {
        Path source = Files.createDirectory(temp.resolve("source"));
        Path folder = Files.createDirectory(source.resolve("folder"));
        Path file = Files.write(folder.resolve("one.txt"), new byte[] { 1 });
        Path index = temp.resolve("index");
        IndexSettings settings = new IndexSettings();
        try (NetPollerService poller = new NetPollerService(index, settings, Collections.singletonList(source))) {
            try (LuceneIndexer blocker = new LuceneIndexer(index, settings)) {
                blocker.commit();
                NetPollerService.PollStats failed = poller.pollNowAsync().get(30, TimeUnit.SECONDS);
                assertFalse(failed.successful);
                assertEquals(1, failed.failedRoots);
                assertTrue(new SnapshotStore().load(source).isEmpty());
            }
            NetPollerService.PollStats first = poller.pollNowAsync().get(30, TimeUnit.SECONDS);
            assertTrue(first.successful);
            assertEquals(3, docCount(index), "polling must include both source and child directories");
            Files.delete(file); Files.delete(folder);
            NetPollerService.PollStats second = poller.pollNowAsync().get(30, TimeUnit.SECONDS);
            assertTrue(second.successful);
            assertEquals(2, second.deleted);
            assertEquals(1, docCount(index));
        }
    }

    @Test
    void shouldCancelRunningRequestWithoutPublishingAndAllowNextRound() throws Exception {
        Path source = Files.createDirectory(temp.resolve("source"));
        Files.write(source.resolve("one.txt"), new byte[] { 1 });
        try (NetPollerService poller = new NetPollerService(temp.resolve("index"), new IndexSettings(), Collections.singletonList(source))) {
            IndexWriteCoordinator.run(() -> {
                Future<NetPollerService.PollStats> future = poller.pollNowAsync();
                awaitWorkersWaiting(poller, 1);
                assertTrue(future.cancel(true));
            });
            awaitNoPending(poller);
            assertTrue(new SnapshotStore().load(source).isEmpty());
            assertTrue(poller.pollNowAsync().get(30, TimeUnit.SECONDS).successful);
        }
    }

    private static int docCount(Path index) throws Exception {
        try (FSDirectory directory = FSDirectory.open(index); DirectoryReader reader = DirectoryReader.open(directory)) {
            return reader.numDocs();
        }
    }

    private static AtomicInteger pending(NetPollerService poller) throws Exception {
        Field field = NetPollerService.class.getDeclaredField("pendingRoots");
        field.setAccessible(true);
        return (AtomicInteger) field.get(poller);
    }

    private static void awaitWorkersWaiting(NetPollerService poller, int roots) {
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (pending(poller).get() != roots && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals(roots, pending(poller).get());
            Thread.sleep(350);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static void awaitNoPending(NetPollerService poller) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (pending(poller).get() != 0 && System.nanoTime() < deadline) Thread.sleep(10);
        assertEquals(0, pending(poller).get(), "cancelled queued root futures must release pending count");
    }
}
