package org.abitware.docfinder.index;

import org.abitware.docfinder.util.Utils;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class IndexRecoveryCommitTest {
    @TempDir Path temporary;
    private String originalDirectory;

    @BeforeEach
    void isolateRegistry() {
        originalDirectory = System.getProperty("user.dir");
        System.setProperty("user.dir", temporary.toString());
    }

    @AfterEach
    void restoreRegistry() {
        try { Tombstones.close(); }
        finally { System.setProperty("user.dir", originalDirectory); }
    }

    @Test
    void shouldRetainRecoveryEvidenceUntilTheMainIndexCommitSucceeds() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("restored"));
        String key = Utils.normalizeForIndex(source);
        Tombstones.record(key);
        Tombstones.flush();
        Path index = temporary.resolve("index");
        try (LuceneIndexer indexer = new LuceneIndexer(index, new IndexSettings())) {
            assertTrue(indexer.applyPrepared(LuceneIndexer.prepareUpsert(source, index, new IndexSettings())));
            assertTrue(Tombstones.contains(key), "An in-memory update is not durable restoration");
            indexer.commit();
            assertFalse(Tombstones.contains(key));
        }
        try (FSDirectory directory = FSDirectory.open(index); DirectoryReader reader = DirectoryReader.open(directory)) {
            assertEquals(1, new IndexSearcher(reader).count(new TermQuery(new Term("path", key))));
        }
    }

    @Test
    void shouldKeepRecoveryEvidenceWhenMainCommitFails() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("restored"));
        Path index = temporary.resolve("index");
        String key = Utils.normalizeForIndex(source);
        Tombstones.record(key);
        Tombstones.flush();
        LuceneIndexer indexer = new LuceneIndexer(index, new IndexSettings());
        try {
            assertTrue(indexer.applyPrepared(LuceneIndexer.prepareUpsert(source, index, new IndexSettings())));
            Field writerField = LuceneIndexer.class.getDeclaredField("writer");
            writerField.setAccessible(true);
            ((IndexWriter) writerField.get(indexer)).rollback();
            assertThrows(IOException.class, indexer::commit);
            Tombstones.close();
            assertTrue(Tombstones.contains(key), "A failed main commit must survive a registry reopen");
        } finally {
            try { indexer.close(); } catch (IOException ignored) { }
        }
    }

    @Test
    void shouldNotDropEvidenceOnFailedSourcePreparation() throws Exception {
        Path source = temporary.resolve("still-missing");
        String key = Utils.normalizeForIndex(source);
        Tombstones.record(key);
        Tombstones.flush();
        try (LuceneIndexer indexer = new LuceneIndexer(temporary.resolve("index"), new IndexSettings())) {
            assertThrows(IOException.class, () -> indexer.tryUpsertFile(source));
            indexer.commit();
            assertTrue(Tombstones.contains(key));
        }
    }

    @Test
    void shouldRefuseOldPreparedContentAfterItsProtectionWasEvicted() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("target"));
        Path index = temporary.resolve("index");
        LuceneIndexer.PreparedUpsert old = LuceneIndexer.prepareUpsert(source, index, new IndexSettings());
        old.document.add(new StoredField("stale-marker", "old"));
        try (LuceneIndexer indexer = new LuceneIndexer(index, new IndexSettings())) {
            assertTrue(indexer.tryUpsertFile(source));
            indexer.commit();
            Method record = LuceneIndexer.class.getDeclaredMethod("recordUpsert", String.class);
            record.setAccessible(true);
            for (int i = 0; i < 4096; i++) record.invoke(null, Utils.normalizeForIndex(temporary.resolve("newer-" + i)));
            assertEquals(-1, LuceneIndexer.upsertedAt(source.toString()));
            assertFalse(indexer.applyPrepared(old));
            indexer.commit();
        }
        try (FSDirectory directory = FSDirectory.open(index); DirectoryReader reader = DirectoryReader.open(directory)) {
            assertEquals(1, reader.numDocs());
            assertNull(reader.document(reader.maxDoc() - 1).get("stale-marker"));
        }
    }

    @Test
    void shouldNotApplyPreparedContentAfterANewerDeletion() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("target"));
        Path index = temporary.resolve("index");
        LuceneIndexer.PreparedUpsert old = LuceneIndexer.prepareUpsert(source, index, new IndexSettings());
        try (LuceneIndexer indexer = new LuceneIndexer(index, new IndexSettings())) {
            assertTrue(indexer.tryDeletePath(source));
            indexer.commit();
            assertFalse(indexer.applyPrepared(old));
            indexer.commit();
            assertTrue(Tombstones.contains(Utils.normalizeForIndex(source)));
        }
        try (FSDirectory directory = FSDirectory.open(index); DirectoryReader reader = DirectoryReader.open(directory)) {
            assertEquals(0, reader.numDocs(), "An obsolete prepared read must not undo a committed deletion");
        }
    }
}
