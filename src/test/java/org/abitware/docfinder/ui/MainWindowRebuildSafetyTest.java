package org.abitware.docfinder.ui;

import org.abitware.docfinder.index.IndexSettings;
import org.abitware.docfinder.index.Tombstones;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

class MainWindowRebuildSafetyTest {
    @TempDir Path temporary;
    private final String originalDirectory = System.getProperty("user.dir");

    @AfterEach
    void restore() {
        Tombstones.close();
        System.setProperty("user.dir", originalDirectory);
    }

    @Test
    void shouldRejectLucene8EvenWhenIndexExists() throws Exception {
        unpackLegacy(temporary);
        java.lang.reflect.Method ready = MainWindow.class.getDeclaredMethod("indexReady", Path.class);
        ready.setAccessible(true);
        assertEquals(false, ready.invoke(null, temporary.resolve("index")),
                "an old commit is not a usable search index");
    }

    @Test
    void shouldRebuildDespiteResidualLockFileWithoutDeletingOldCommitEarly() throws Exception {
        System.setProperty("user.dir", temporary.toString());
        Path index = temporary.resolve("index");
        seed(index);
        assertTrue(Files.exists(index.resolve(IndexWriter.WRITE_LOCK_NAME)));
        Path root = Files.createDirectory(temporary.resolve("source"));
        Files.writeString(root.resolve("hello.txt"), "fresh");
        assertTrue(MainWindow.rebuildIndex(index, Collections.singletonList(root), new IndexSettings()) > 0);
        try (FSDirectory directory = FSDirectory.open(index);
                DirectoryReader reader = DirectoryReader.open(directory)) {
            assertEquals(2, reader.numDocs(), "one folder plus one replacement file");
        }
    }

    @Test
    void shouldKeepCommitAndLockWhenAnotherProcessOwnsWriter() throws Exception {
        System.setProperty("user.dir", temporary.toString());
        Path index = temporary.resolve("index");
        seed(index);
        Path signal = temporary.resolve("locked");
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), ExternalWriter.class.getName(),
                index.toString(), signal.toString()).redirectErrorStream(true)
                .redirectOutput(temporary.resolve("external-writer.log").toFile()).start();
        try {
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
            while (!Files.exists(signal) && child.isAlive() && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertTrue(Files.exists(signal), "external writer must acquire real lock");
            Path root = Files.createDirectory(temporary.resolve("source"));
            assertThrows(org.apache.lucene.store.LockObtainFailedException.class,
                    () -> MainWindow.rebuildIndex(index, Collections.singletonList(root), new IndexSettings()));
            try (FSDirectory directory = FSDirectory.open(index);
                    DirectoryReader reader = DirectoryReader.open(directory)) {
                assertEquals("old", reader.storedFields().document(0).get("path"));
            }
            assertTrue(Files.exists(index.resolve(IndexWriter.WRITE_LOCK_NAME)));
        } finally {
            child.getOutputStream().write(1);
            child.getOutputStream().close();
            if (!child.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                child.destroyForcibly();
                child.waitFor();
            }
        }
        assertEquals(0, child.exitValue());
    }

    @Test
    void shouldPreservePreviousCommitWhenRebuildCannotReadSource() throws Exception {
        System.setProperty("user.dir", temporary.toString());
        Path index = temporary.resolve("index");
        seed(index);
        // A pre-interrupted owner aborts the full transaction before it can publish deleteAll.
        Thread.currentThread().interrupt();
        try {
            assertThrows(java.io.IOException.class, () -> MainWindow.rebuildIndex(index,
                    Collections.singletonList(temporary.resolve("missing")), new IndexSettings()));
        } finally {
            Thread.interrupted();
        }
        try (FSDirectory directory = FSDirectory.open(index);
                DirectoryReader reader = DirectoryReader.open(directory)) {
            assertEquals(1, reader.numDocs());
            assertEquals("old", reader.storedFields().document(0).get("path"));
        }
    }

    @Test
    void shouldRollbackUiRebuildWhenOwnerFailsAfterDeleteAll() throws Exception {
        System.setProperty("user.dir", temporary.toString());
        Path index = temporary.resolve("index");
        seed(index);
        AssertionError failure = new AssertionError("source normalization failed after deleteAll");
        Path failingRoot = (Path) java.lang.reflect.Proxy.newProxyInstance(Path.class.getClassLoader(),
                new Class<?>[] {Path.class}, (proxy, method, args) -> { throw failure; });
        assertSame(failure, assertThrows(AssertionError.class, () -> MainWindow.rebuildIndex(index,
                Collections.singletonList(failingRoot), new IndexSettings())));
        try (FSDirectory directory = FSDirectory.open(index);
                DirectoryReader reader = DirectoryReader.open(directory)) {
            assertEquals(1, reader.numDocs());
            assertEquals("old", reader.storedFields().document(0).get("path"));
        }
    }

    private static void seed(Path index) throws Exception {
        try (FSDirectory directory = FSDirectory.open(index);
                IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig(new StandardAnalyzer()))) {
            Document document = new Document();
            document.add(new StringField("path", "old", Field.Store.YES));
            writer.addDocument(document);
            writer.commit();
        }
    }

    public static class ExternalWriter {
        public static void main(String[] args) throws Exception {
            try (FSDirectory directory = FSDirectory.open(Path.of(args[0]));
                    IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig(new StandardAnalyzer()))) {
                Files.writeString(Path.of(args[1]), "locked");
                System.in.read();
            }
        }
    }

    static void unpackLegacy(Path base) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(
                MainWindowRebuildSafetyTest.class.getResourceAsStream("/index/lucene8.zip"))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                Path target = base.resolve(entry.getName());
                Files.createDirectories(target.getParent());
                Files.copy(zip, target);
            }
        }
    }
}
