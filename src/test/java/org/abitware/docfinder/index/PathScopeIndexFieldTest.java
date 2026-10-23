package org.abitware.docfinder.index;

import org.abitware.docfinder.index.content.DocumentBuilder;
import org.abitware.docfinder.util.PathScope;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A2: every indexed file/folder document carries a searchable, not-stored
 * {@code path_scope} equal to {@link PathScope#normalizeIndexedPath}, while
 * the stored {@code path} field keeps its legacy format as update key.
 */
@Execution(ExecutionMode.SAME_THREAD)
class PathScopeIndexFieldTest {

    @TempDir
    Path fixtureRoot;

    private Path newSource() throws Exception {
        Path source = fixtureRoot.resolve("source");
        Files.createDirectories(source.resolve("SubDir"));
        Files.write(source.resolve("a.txt"), "hello".getBytes("UTF-8"));
        Files.write(source.resolve("SubDir").resolve("b.txt"), "world".getBytes("UTF-8"));
        return source;
    }

    @Test
    void shouldWritePathScopeForFilesAndFolders() throws Exception {
        Path source = newSource();
        Path index = fixtureRoot.resolve("index");
        try (LuceneIndexer indexer = new LuceneIndexer(index, new IndexSettings())) {
            indexer.indexFolder(source);
        }

        try (IndexReader r = DirectoryReader.open(FSDirectory.open(index))) {
            IndexSearcher s = new IndexSearcher(r);
            // 2 dirs (source root + SubDir) + 2 files
            assertEquals(4, r.numDocs());
            for (int i = 0; i < r.maxDoc(); i++) {
                Document d = r.document(i);
                String path = d.get("path");
                assertNotNull(path);
                // path_scope is indexed but not stored → searchable, absent from stored fields
                assertNull(d.get("path_scope"));
                String scope = PathScope.normalizeIndexedPath(java.nio.file.Paths.get(path).isAbsolute()
                        ? java.nio.file.Paths.get(path) : fixtureRoot.resolve(path));
                assertEquals(1, s.count(new TermQuery(new Term("path_scope", scope))),
                        "path_scope must equal normalizeIndexedPath for " + path);
            }
            // original stored path format unchanged (backslash form on Windows)
            Document aDoc = s.search(new TermQuery(new Term("path_scope",
                    PathScope.normalizeIndexedPath(source.resolve("a.txt")))), 1)
                    .scoreDocs[0].doc >= 0
                    ? r.document(s.search(new TermQuery(new Term("path_scope",
                            PathScope.normalizeIndexedPath(source.resolve("a.txt")))), 1)
                            .scoreDocs[0].doc) : null;
            assertNotNull(aDoc);
            assertEquals(org.abitware.docfinder.util.Utils
                    .normalizeForIndex(source.resolve("a.txt")), aDoc.get("path"));
        }
    }

    @Test
    void shouldSupportPrefixQueryForDescendants() throws Exception {
        Path source = newSource();
        Path index = fixtureRoot.resolve("index");
        try (LuceneIndexer indexer = new LuceneIndexer(index, new IndexSettings())) {
            indexer.indexFolder(source);
        }
        try (IndexReader r = DirectoryReader.open(FSDirectory.open(index))) {
            IndexSearcher s = new IndexSearcher(r);
            String dir = PathScope.normalizeIndexedPath(source.resolve("SubDir"));
            // Descendant prefix matches children only; the dir itself is an
            // exact-term match (the search layer ORs both for FOLDER scope).
            assertEquals(1, s.count(new PrefixQuery(new Term("path_scope",
                    PathScope.descendantPrefix(dir)))),
                    "descendants of SubDir = b.txt only");
            assertEquals(1, s.count(new TermQuery(new Term("path_scope", dir))),
                    "SubDir itself via exact term");
            // sibling-prefix safety: "SubDirX" would not match "SubDir/…"
            Path sibling = source.resolve("SubDirX");
            Files.createDirectories(sibling);
            assertNotEquals(PathScope.descendantPrefix(dir),
                    PathScope.descendantPrefix(PathScope.normalizeIndexedPath(sibling)));
        }
    }

    @Test
    void shouldWritePathScopeOnUpsert() throws Exception {
        Path source = newSource();
        Path index = fixtureRoot.resolve("index");
        try (LuceneIndexer indexer = new LuceneIndexer(index, new IndexSettings())) {
            indexer.indexFolder(source);
        }
        // modify file then upsert via a second writer session
        Files.write(source.resolve("a.txt"), "hello again".getBytes("UTF-8"));
        try (LuceneIndexer indexer = new LuceneIndexer(index, new IndexSettings())) {
            indexer.upsertFile(source.resolve("a.txt"));
            indexer.commit();
        }
        try (IndexReader r = DirectoryReader.open(FSDirectory.open(index))) {
            IndexSearcher s = new IndexSearcher(r);
            String scope = PathScope.normalizeIndexedPath(source.resolve("a.txt"));
            assertEquals(1, s.count(new TermQuery(new Term("path_scope", scope))),
                    "upsert must refresh path_scope, not duplicate");
        }
    }

    @Test
    void shouldWritePathScopeViaDocumentBuilder() throws Exception {
        DocumentBuilder b = new DocumentBuilder(new IndexSettings());
        Path dir = fixtureRoot.resolve("d");
        Files.createDirectories(dir);
        Path f = fixtureRoot.resolve("f.txt");
        Files.write(f, "x".getBytes("UTF-8"));

        BasicFileAttributes da = Files.readAttributes(dir, BasicFileAttributes.class);
        BasicFileAttributes fa = Files.readAttributes(f, BasicFileAttributes.class);

        Document dirDoc = b.createDirectoryDocument(dir, da);
        assertEquals(PathScope.normalizeIndexedPath(dir),
                dirDoc.getField("path_scope").stringValue());
        Document fileDoc = b.createFileDocument(f, fa);
        assertEquals(PathScope.normalizeIndexedPath(f),
                fileDoc.getField("path_scope").stringValue());
        assertEquals(org.abitware.docfinder.util.Utils.normalizeForIndex(f),
                fileDoc.get("path"));
    }
}
