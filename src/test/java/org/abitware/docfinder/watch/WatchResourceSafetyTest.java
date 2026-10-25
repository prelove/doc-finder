package org.abitware.docfinder.watch;

import org.abitware.docfinder.index.IndexSettings;
import org.abitware.docfinder.index.IndexWriteCoordinator;
import org.abitware.docfinder.index.LuceneIndexer;
import org.abitware.docfinder.util.AppPaths;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Resource-safety contract tests for the watch/poller/snapshot subsystem:
 * overflow marks a source reconciliation-needed instead of dropping events,
 * the pending map is capacity-bounded, polls never overlap, an unreadable
 * baseline skips the diff, and an incomplete scan never deletes.
 */
@Execution(ExecutionMode.SAME_THREAD)
class WatchResourceSafetyTest {

    @TempDir
    Path fixtureRoot;

    private Path source;
    private Path index;
    private IndexSettings settings;
    private LiveIndexService live;
    private NetPollerService poller;

    @BeforeEach
    void setUp() throws Exception {
        source = fixtureRoot.resolve("source");
        index = fixtureRoot.resolve("index");
        Files.createDirectories(source);
        settings = new IndexSettings();
    }

    @AfterEach
    void tearDown() {
        if (live != null) live.close();
        if (poller != null) poller.stop();
    }

    private static void write(Path p, String content) throws Exception {
        Files.write(p, content.getBytes("UTF-8"));
    }

    private static int docCount(Path indexDir) throws Exception {
        try (FSDirectory dir = FSDirectory.open(indexDir);
             DirectoryReader reader = DirectoryReader.open(dir)) {
            return reader.numDocs();
        }
    }

    @Test
    void shouldMarkReconcileOnWatchOverflow() throws Exception {
        live = new LiveIndexService(index, settings, Collections.singletonList(source));
        live.onOverflow(source.resolve("some-dir"));
        assertTrue(live.pendingReconcileRoots().contains(source),
                "watch OVERFLOW must mark the owning source reconciliation-needed");
        assertEquals(1, live.pendingReconcileRoots().size());
    }

    @Test
    void shouldMarkReconcileWhenPendingMapSaturated() throws Exception {
        live = new LiveIndexService(index, settings, Collections.singletonList(source));
        int cap = 50_000;
        for (int i = 0; i < cap + 64; i++) {
            live.onChange("MODIFY", source.resolve("flood-" + i + ".txt"));
        }
        assertTrue(live.pendingReconcileRoots().contains(source),
                "a saturated pending map must flip to reconcile-needed, not grow unboundedly");
    }

    @Test
    void shouldNotOverlapConcurrentPolls() throws Exception {
        poller = new NetPollerService(index, settings, Collections.singletonList(source));
        poller.start(60); // long interval — only manual polls in this test

        // Hold the write lock so the first poll is guaranteed in-flight when
        // the second one is requested.
        java.util.concurrent.atomic.AtomicReference<NetPollerService.PollStats> second =
                new java.util.concurrent.atomic.AtomicReference<>();
        IndexWriteCoordinator.run(() -> {
            Future<NetPollerService.PollStats> first = poller.pollNowAsync();
            assertNotNull(first);
            try {
                Thread.sleep(400); // first poll: scan done, blocked on the write lock
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            try {
                second.set(poller.pollNowAsync().get(5, TimeUnit.SECONDS));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            assertFalse(first.isDone(), "first poll must still be in flight behind the write lock");
        });

        assertEquals(0L, second.get().scannedFiles,
                "a second poll must be skipped while one is in flight");
    }

    @Test
    void shouldSkipRootWhenBaselineUnreadable() throws Exception {
        // Make the stored baseline path a directory → load() hits an I/O
        // error → returns null → the root is skipped rather than diffed
        // against a broken baseline.
        String raw = source.toAbsolutePath().toString();
        String norm = org.abitware.docfinder.util.Utils.isWindows()
                ? org.abitware.docfinder.util.Utils.canonicalizeWinPathString(raw) : raw;
        String key = norm.replace(':', '_').replace('\\', '_').replace('/', '_');
        if (key.isEmpty()) key = "root";
        Path snapDir = AppPaths.getBaseDir().resolve("snapshots");
        Files.createDirectories(snapDir);
        Files.createDirectory(snapDir.resolve(key + ".txt"));

        poller = new NetPollerService(index, settings, Collections.singletonList(source));
        poller.start(60);
        NetPollerService.PollStats stats = poller.pollNowAsync().get(30, TimeUnit.SECONDS);

        assertEquals(0L, stats.created + stats.modified + stats.deleted,
                "unreadable baseline must skip the root, not fabricate a diff");
    }

    @Test
    void shouldSuppressDeletesOnIncompleteScan() throws Exception {
        write(source.resolve("gone.txt"), "lorem");
        write(source.resolve("kept.txt"), "lorem");
        try (LuceneIndexer idx = new LuceneIndexer(index, settings)) {
            idx.indexFolder(source);
        }
        // indexFolder records the source dir itself as a folder doc → 3 docs
        assertEquals(3, docCount(index));

        Map<String, SnapshotStore.Entry> oldSnap = new HashMap<>();
        oldSnap.put(source.resolve("gone.txt").toAbsolutePath().toString(),
                new SnapshotStore.Entry(5, 1));
        oldSnap.put(source.resolve("kept.txt").toAbsolutePath().toString(),
                new SnapshotStore.Entry(5, 1));
        // "gone" is absent from the new snapshot.
        Map<String, SnapshotStore.Entry> newSnap = new HashMap<>();
        newSnap.put(source.resolve("kept.txt").toAbsolutePath().toString(),
                new SnapshotStore.Entry(5, 1));

        poller = new NetPollerService(index, settings, Collections.singletonList(source));

        // Incomplete scan: unproven absence must never delete.
        IndexWriteCoordinator.run(() -> {
            try (LuceneIndexer idx = new LuceneIndexer(index, settings)) {
                NetPollerService.RootStats rs = new NetPollerService.RootStats();
                poller.applySnapshotDiff(idx, oldSnap, newSnap, false, rs);
                assertEquals(0, rs.deleted, "partial scan must suppress deletes");
                idx.commit();
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
        });
        assertEquals(3, docCount(index),
                "partial scan must not delete paths it failed to read");

        // Complete scan: the same diff may delete.
        IndexWriteCoordinator.run(() -> {
            try (LuceneIndexer idx = new LuceneIndexer(index, settings)) {
                NetPollerService.RootStats rs = new NetPollerService.RootStats();
                poller.applySnapshotDiff(idx, oldSnap, newSnap, true, rs);
                assertEquals(1, rs.deleted, "complete scan deletes proven-absent paths");
                idx.commit();
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
        });
        assertEquals(2, docCount(index),
                "complete scan may delete proven-absent paths");
    }

    @Test
    void shouldRoundTripSnapshotWithoutTmpResidue() throws Exception {
        SnapshotStore store = new SnapshotStore();
        Map<String, SnapshotStore.Entry> snap = new HashMap<>();
        snap.put(source.resolve("a.txt").toAbsolutePath().toString(),
                new SnapshotStore.Entry(3, 42));
        assertTrue(store.save(source, snap));

        Map<String, SnapshotStore.Entry> loaded = store.load(source);
        assertNotNull(loaded);
        SnapshotStore.Entry e = loaded.get(source.resolve("a.txt").toAbsolutePath().toString());
        assertNotNull(e);
        assertEquals(3, e.size);
        assertEquals(42, e.mtime);

        // tmp file must not linger after an atomic save
        Path snapDir = AppPaths.getBaseDir().resolve("snapshots");
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(snapDir, "*.tmp")) {
            assertFalse(ds.iterator().hasNext(), "atomic save must not leave .tmp residue");
        }
    }
}
