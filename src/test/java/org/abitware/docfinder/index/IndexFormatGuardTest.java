package org.abitware.docfinder.index;

import org.abitware.docfinder.util.AppPaths;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.Lock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

class IndexFormatGuardTest {
    @TempDir Path temporary;
    String originalDirectory;
    Path base;

    @BeforeEach
    void isolate() throws Exception {
        originalDirectory = System.getProperty("user.dir");
        System.setProperty("user.dir", temporary.toString());
        base = AppPaths.getBaseDir();
        try (ZipInputStream zip = new ZipInputStream(getClass().getResourceAsStream("/index/lucene8.zip"))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                Path file = base.resolve(entry.getName());
                Files.createDirectories(file.getParent());
                Files.copy(zip, file);
            }
        }
    }

    @AfterEach
    void restore() {
        Tombstones.close();
        System.setProperty("user.dir", originalDirectory);
    }

    @Test
    void shouldPreserveBothOldIndexesAndPersistRebuildStateAcrossStartup() throws Exception {
        IndexFormatGuard.Result result = IndexFormatGuard.prepare(base.resolve("index"), base);
        assertEquals(IndexFormatGuard.State.INDEX_REBUILD_REQUIRED, result.state());
        assertEquals(2, result.backups().size());
        for (Path backup : result.backups()) {
            assertTrue(backup.getFileName().toString().contains(".lucene8.bak-"));
            assertTrue(Files.exists(backup.resolve("segments_1")), "backup retains binary commit");
        }
        assertFalse(Files.exists(base.resolve("index")));
        assertFalse(Files.exists(base.resolve("deleted-paths-index")));
        assertEquals(IndexFormatGuard.State.INDEX_REBUILD_REQUIRED,
                IndexFormatGuard.prepare(base.resolve("index"), base).state());
    }

    @Test
    void shouldReportReadyForFreshInstallWithoutAnyIndex() throws Exception {
        Path freshBase = temporary.resolve("fresh-install");
        Path index = freshBase.resolve("index");
        IndexFormatGuard.Result result = IndexFormatGuard.prepare(index, freshBase);
        assertEquals(IndexFormatGuard.State.READY, result.state());
        assertTrue(result.backups().isEmpty());
        assertFalse(IndexFormatGuard.isReady(index));
        assertFalse(Files.exists(freshBase.resolve("index.rebuild-required")));
    }

    @Test
    void shouldRebuildSearchableIndexAndAllowUpsertWithOldRegistry() throws Exception {
        Path index = base.resolve("index");
        IndexFormatGuard.prepare(index, base);
        Path root = Files.createDirectory(temporary.resolve("source"));
        Path file = Files.writeString(root.resolve("hello.txt"), "rebuiltfixture searchable");
        try (LuceneIndexer writer = new LuceneIndexer(index, new IndexSettings())) {
            assertTrue(writer.indexFolders(List.of(root), true) > 0);
        }
        IndexFormatGuard.rebuildCompleted(index);
        assertTrue(IndexFormatGuard.isReady(index));
        try (FSDirectory directory = FSDirectory.open(index);
                DirectoryReader reader = DirectoryReader.open(directory)) {
            org.apache.lucene.search.IndexSearcher searcher = new org.apache.lucene.search.IndexSearcher(reader);
            assertEquals(1, searcher.count(new org.apache.lucene.search.TermQuery(
                    new org.apache.lucene.index.Term("content", "rebuiltfixture"))));
        }
        Files.writeString(file, "updatedfixture");
        try (LuceneIndexer writer = new LuceneIndexer(index, new IndexSettings())) {
            assertTrue(writer.tryUpsertFile(file));
            writer.commit();
        }
        assertTrue(Tombstones.isEmpty());
    }

    @Test
    void shouldStopWithoutRenamingWhenOldDirectoryHasARealWriterLock() throws Exception {
        Path index = base.resolve("index");
        try (FSDirectory directory = FSDirectory.open(index);
                Lock lock = directory.obtainLock(IndexWriter.WRITE_LOCK_NAME)) {
            assertThrows(org.apache.lucene.store.LockObtainFailedException.class,
                    () -> IndexFormatGuard.prepare(index, base));
            assertTrue(Files.exists(index.resolve("segments_1")));
            lock.ensureValid();
        }
    }

    @Test
    void shouldResetRecoveryEvidenceDurablyAfterSuccessfulFullRebuild() throws Exception {
        Path index = base.resolve("index");
        IndexFormatGuard.prepare(index, base);
        Tombstones.record("/unrelated/old.txt");
        Tombstones.flush();
        Path root = Files.createDirectory(temporary.resolve("source"));
        Files.writeString(root.resolve("hello.txt"), "fresh");
        try (LuceneIndexer writer = new LuceneIndexer(index, new IndexSettings())) {
            writer.indexFolders(List.of(root), true);
        }
        Tombstones.close();
        assertTrue(Tombstones.isEmpty());
    }
}
