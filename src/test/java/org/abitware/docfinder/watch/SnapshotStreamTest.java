package org.abitware.docfinder.watch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotStreamTest {
    @TempDir Path temp;

    @Test
    void shouldSpillAndMergeMoreThanOneChunkWithoutLosingRecords() throws Exception {
        SnapshotStore store = new SnapshotStore(temp.resolve("snapshots"));
        try (SnapshotStore.Builder builder = store.newBuilder()) {
            for (int i = 20000; i >= 0; i--) builder.add(String.format("/source/%05d", i), new SnapshotStore.Entry(i, 42, i % 2 == 0));
            try (Stream<Path> files = Files.list(temp.resolve("snapshots"))) {
                assertTrue(files.count() > 0, "records beyond the fixed chunk must already be on disk before finish");
            }
            try (SnapshotStore.Staged staged = builder.finish(); SnapshotStore.Cursor cursor = staged.cursor()) {
                for (int i = 0; i <= 20000; i++) {
                    SnapshotStore.Record record = cursor.next();
                    assertNotNull(record);
                    assertEquals(String.format("/source/%05d", i), record.path);
                    assertEquals(i, record.entry.size);
                    assertEquals(i % 2 == 0, record.entry.directory);
                }
                assertNull(cursor.next());
            }
        }
        try (Stream<Path> files = Files.list(temp.resolve("snapshots"))) { assertEquals(0, files.count()); }
    }

    @Test
    void shouldCleanSpilledRunsWhenScanAborts() throws Exception {
        SnapshotStore store = new SnapshotStore(temp.resolve("snapshots"));
        try (SnapshotStore.Builder builder = store.newBuilder()) {
            for (int i = 0; i < 9000; i++) builder.add("/source/" + i, new SnapshotStore.Entry(i, 1));
        }
        try (Stream<Path> files = Files.list(temp.resolve("snapshots"))) { assertEquals(0, files.count()); }
    }

    @Test
    void shouldReadLegacyUnorderedSnapshotsAndRejectMalformedBaseline() throws Exception {
        Path directory = temp.resolve("snapshots");
        SnapshotStore store = new SnapshotStore(directory);
        assertTrue(store.save(temp, Collections.singletonMap("unused", new SnapshotStore.Entry(0, 0))));
        Path baseline;
        try (Stream<Path> files = Files.list(directory)) { baseline = files.findFirst().get(); }
        Files.write(baseline, Arrays.asList("/source/z|8|9", "/source/a|2|3"), StandardCharsets.UTF_8);
        try (SnapshotStore.Cursor cursor = store.open(temp)) {
            assertEquals("/source/a", cursor.next().path);
            assertEquals("/source/z", cursor.next().path);
            assertNull(cursor.next());
        }
        Files.write(baseline, Arrays.asList("/source/a|broken|3"), StandardCharsets.UTF_8);
        assertNull(store.load(temp), "an unreadable baseline must never look like an empty one");
        try (Stream<Path> files = Files.list(directory)) { assertEquals(1, files.count(), "legacy conversion must clean temporary runs"); }
    }
}
