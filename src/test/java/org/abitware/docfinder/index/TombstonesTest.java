package org.abitware.docfinder.index;

import org.abitware.docfinder.util.Utils;
import org.apache.lucene.analysis.core.KeywordAnalyzer;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class TombstonesTest {
    @TempDir Path temporary;
    private String originalDirectory;

    @BeforeEach
    void useIsolatedDirectory() {
        originalDirectory = System.getProperty("user.dir");
        System.setProperty("user.dir", temporary.toString());
    }

    @AfterEach
    void restoreDirectory() {
        try {
            Tombstones.close();
        } finally {
            System.setProperty("user.dir", originalDirectory);
        }
    }

    @Test
    void shouldRetainRecoveryEvidenceBeyondTheSnapshotCapacity() {
        String oldest = key("oldest.txt");
        Tombstones.record(oldest);
        for (int i = 0; i < 4096; i++) {
            Tombstones.record(key("later-" + i));
        }
        for (int i = 0; i < 4096; i++) {
            Tombstones.remove(key("later-" + i));
        }
        assertEquals(Collections.singletonList(oldest), Tombstones.snapshot());
    }

    @Test
    void shouldPersistBatchMutationsWithOneExplicitCommit() {
        Tombstones.recordBatch(Arrays.asList(key("a.txt"), key("b.txt"), key("c.txt")));
        Tombstones.removeBatch(Collections.singletonList(key("b.txt")));
        assertTrue(Tombstones.contains(key("a.txt")));
        assertFalse(Tombstones.contains(key("b.txt")));
        Tombstones.flush();
        Tombstones.close();
        assertEquals(Arrays.asList(key("a.txt"), key("c.txt")), Tombstones.snapshot());
    }

    @Test
    void shouldCommitAndReleaseResourcesOnCloseAndAllowReopen() {
        Tombstones.record(key("close.txt"));
        Tombstones.close();
        Tombstones.close();
        assertTrue(Tombstones.contains(key("close.txt")));
    }

    @Test
    void shouldKeepOldRecoveryEvidenceWhenMigratingTheLegacyFile() throws Exception {
        Path legacy = temporary.resolve(".docfinder/deleted-paths.txt");
        Files.createDirectories(legacy.getParent());
        String oldPath = key("restored-from-old-backup.txt");
        Files.write(legacy, Collections.singletonList(oldPath + "|1"), StandardCharsets.UTF_8);
        assertEquals(Collections.singletonList(oldPath), Tombstones.snapshot());
    }

    @Test
    void shouldPageEveryRecordBeyond4096AndStopAtTheTail() {
        for (int i = 0; i < 4300; i++) {
            Tombstones.record(key(String.format("%05d.txt", i)));
        }
        assertEquals(4096, Tombstones.snapshot().size());
        List<String> recovered = new ArrayList<>();
        String cursor = null;
        while (true) {
            List<String> page = Tombstones.snapshotAfter(cursor, 137,
                    Collections.singletonList(temporary.resolve("source")));
            if (page.isEmpty()) {
                break;
            }
            assertTrue(page.size() <= 137);
            recovered.addAll(page);
            cursor = page.get(page.size() - 1);
        }
        assertEquals(4300, recovered.size());
        assertEquals(key("00000.txt"), recovered.get(0));
        assertEquals(key("04299.txt"), recovered.get(4299));
        assertEquals(4300, recovered.stream().distinct().count());
    }

    @Test
    void shouldFilterRootsWithDirectoryBoundariesAndExclusiveCursor() {
        Tombstones.record(key("a.txt"));
        Tombstones.record(key("b.txt"));
        String sibling = Utils.normalizeForIndex(temporary.resolve("source-sibling/hidden.txt"));
        Tombstones.record(sibling);
        assertEquals(Collections.singletonList(key("b.txt")), Tombstones.snapshotAfter(
                key("a.txt"), 20, Collections.singletonList(temporary.resolve("source"))));
        assertTrue(Tombstones.snapshotAfter(null, 20, Collections.emptyList()).isEmpty());
        assertTrue(Tombstones.snapshotAfter(key("b.txt"), 20,
                Collections.singletonList(temporary.resolve("source"))).isEmpty());
        assertTrue(Tombstones.snapshotAfter(null, 0,
                Collections.singletonList(temporary.resolve("source"))).isEmpty());
    }

    @Test
    void shouldPersistRemovalAndRetainMigrationBackupAcrossReopen() throws Exception {
        Path legacy = temporary.resolve(".docfinder/deleted-paths.txt");
        Files.createDirectories(legacy.getParent());
        String content = key("a.txt") + "|1\n" + key("b.txt") + "|2\n";
        Files.write(legacy, content.getBytes(StandardCharsets.UTF_8));
        assertTrue(Tombstones.contains(key("a.txt")));
        Path backup = legacy.resolveSibling("deleted-paths.txt.bak");
        assertArrayEquals(content.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(backup));
        Tombstones.remove(key("a.txt"));
        Tombstones.flush();
        closeCurrentStore();
        assertFalse(Tombstones.contains(key("a.txt")));
        assertTrue(Tombstones.contains(key("b.txt")));
        assertFalse(Tombstones.isEmpty());
    }

    @Test
    void shouldNotReimportRemovedRecordsIfLegacyRenameHadFailed() throws Exception {
        Path legacy = temporary.resolve(".docfinder/deleted-paths.txt");
        Files.createDirectories(legacy.getParent());
        Files.write(legacy, Collections.singletonList(key("restored.txt") + "|1"), StandardCharsets.UTF_8);
        assertTrue(Tombstones.contains(key("restored.txt")));
        Files.copy(legacy.resolveSibling("deleted-paths.txt.bak"), legacy);
        Tombstones.remove(key("restored.txt"));
        Tombstones.flush();
        closeCurrentStore();
        assertFalse(Tombstones.contains(key("restored.txt")));
    }

    @Test
    void shouldKeepPersistedEvidenceWhenRemovalCannotOpenTheStore() throws Exception {
        Tombstones.record(key("retry-remove.txt"));
        Tombstones.flush();
        closeCurrentStore();
        try (FSDirectory directory = FSDirectory.open(temporary.resolve(".docfinder/deleted-paths-index"));
             IndexWriter foreignWriter = new IndexWriter(directory, new IndexWriterConfig(new KeywordAnalyzer()))) {
            assertThrows(UncheckedIOException.class, () -> Tombstones.remove(key("retry-remove.txt")));
        }
        assertTrue(Tombstones.contains(key("retry-remove.txt")));
        Tombstones.remove(key("retry-remove.txt"));
        Tombstones.flush();
        closeCurrentStore();
        assertFalse(Tombstones.contains(key("retry-remove.txt")));
    }

    @Test
    void shouldReportOpenFailureAndRetryWhenTheDirectoryIsAvailable() throws Exception {
        Path blocked = temporary.resolve(".docfinder/deleted-paths-index");
        Files.createDirectories(blocked.getParent());
        Files.write(blocked, new byte[] {1});
        assertThrows(UncheckedIOException.class, () -> Tombstones.record(key("retry.txt")));
        Files.delete(blocked);
        Tombstones.record(key("retry.txt"));
        Tombstones.flush();
        closeCurrentStore();
        assertTrue(Tombstones.contains(key("retry.txt")));
    }

    @Test
    void shouldRetryFailedCommitWithoutLosingPendingAddsOrRemovals() throws Exception {
        synchronized (lock()) {
            Tombstones.record(key("removed.txt"));
            Tombstones.flush();
            Tombstones.remove(key("removed.txt"));
            Tombstones.record(key("added.txt"));
            Path directory = temporary.resolve(".docfinder/deleted-paths-index");
            List<Path> blockers = new ArrayList<>();
            for (int i = 2; i <= 20; i++) {
                Path blocker = directory.resolve("pending_segments_" + Integer.toString(i, 36));
                Files.createDirectory(blocker);
                blockers.add(blocker);
            }
            try {
                assertThrows(UncheckedIOException.class, Tombstones::flush);
                assertThrows(UncheckedIOException.class, Tombstones::close);
            } finally {
                for (Path blocker : blockers) {
                    Files.deleteIfExists(blocker);
                }
            }
        }
        // No second mutation or explicit flush: the periodic task must retry.
        assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
            Path index = temporary.resolve(".docfinder/deleted-paths-index");
            while (true) {
                try (FSDirectory directory = FSDirectory.open(index);
                     DirectoryReader reader = DirectoryReader.open(directory)) {
                    if (new IndexSearcher(reader).count(new TermQuery(new Term("path", key("added.txt")))) == 1) {
                        break;
                    }
                }
                Thread.sleep(20);
            }
        });
        closeCurrentStore();
        assertFalse(Tombstones.contains(key("removed.txt")));
        assertTrue(Tombstones.contains(key("added.txt")));
    }

    @Test
    void shouldReleaseOldDirectoryLockWhenTheBaseDirectoryChanges() throws Exception {
        Tombstones.record(key("retained.txt"));
        Path oldIndex = temporary.resolve(".docfinder/deleted-paths-index");
        Path secondBase = Files.createDirectory(temporary.resolve("second"));
        System.setProperty("user.dir", secondBase.toString());
        assertTrue(Tombstones.isEmpty());
        try (FSDirectory directory = FSDirectory.open(oldIndex);
             IndexWriter ignored = new IndexWriter(directory, new IndexWriterConfig(new KeywordAnalyzer()))) {
            // A second writer can acquire the old lock only after the old store closes.
        }
        System.setProperty("user.dir", temporary.toString());
        assertTrue(Tombstones.contains(key("retained.txt")));
    }

    @Test
    void shouldRequestFlushWithoutWaitingForTheStoreLock() throws Exception {
        Tombstones.record(key("async.txt"));
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            synchronized (lock()) {
                locked.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        holder.start();
        assertTrue(locked.await(5, TimeUnit.SECONDS));
        try {
            assertTimeoutPreemptively(Duration.ofMillis(500), Tombstones::requestFlush);
        } finally {
            release.countDown();
            holder.join(5000);
        }
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (FSDirectory directory = FSDirectory.open(temporary.resolve(".docfinder/deleted-paths-index"))) {
                while (!DirectoryReader.indexExists(directory)) {
                    Thread.sleep(10);
                }
                try (DirectoryReader reader = DirectoryReader.open(directory)) {
                    assertEquals(1, reader.numDocs());
                }
            }
        });
        closeCurrentStore();
        assertTrue(Tombstones.contains(key("async.txt")));
    }

    private String key(String relative) {
        return Utils.normalizeForIndex(temporary.resolve("source").resolve(relative));
    }

    private static void closeCurrentStore() throws Exception {
        Tombstones.close();
    }

    private static Object lock() {
        try {
            Field lock = Tombstones.class.getDeclaredField("LOCK");
            lock.setAccessible(true);
            return lock.get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
