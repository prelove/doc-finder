package org.abitware.docfinder.search;

import org.abitware.docfinder.index.IndexSettings;
import org.abitware.docfinder.index.LuceneIndexer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A4: ensureDirectoryScopeSupported decides scoped-search eligibility for a
 * reader — old indexes missing path_scope report INDEX_REBUILD_REQUIRED,
 * deleted old docs do not block, mixed indexes still 409, and unscoped
 * search against an old index keeps working untouched by the check.
 */
@Execution(ExecutionMode.SAME_THREAD)
class DirectoryScopeCompatibilityTest {

    @TempDir
    Path fixtureRoot;

    private LuceneSearchService service;

    @AfterEach
    void tearDown() {
        if (service != null) service.close();
    }

    private static Document oldDoc(String path, String name) {
        // Legacy document shape: no path_scope.
        Document d = new Document();
        d.add(new StringField("path", path, Field.Store.YES));
        d.add(new TextField("name", name, Field.Store.YES));
        d.add(new StringField("name_raw", name.toLowerCase(java.util.Locale.ROOT), Field.Store.NO));
        d.add(new StringField("ext", "txt", Field.Store.YES));
        d.add(new StringField("kind", "file", Field.Store.YES));
        return d;
    }

    private static Document newDoc(String path, String name) {
        Document d = oldDoc(path, name);
        d.add(new StringField("path_scope",
                org.abitware.docfinder.util.PathScope.normalizeIndexedPath(
                        java.nio.file.Paths.get(path)), Field.Store.NO));
        return d;
    }

    private IndexWriter openWriter(Path index) throws Exception {
        Files.createDirectories(index);
        return new IndexWriter(FSDirectory.open(index),
                new IndexWriterConfig(new StandardAnalyzer()));
    }

    private Path sourceDir() throws Exception {
        Path src = fixtureRoot.resolve("src");
        Files.createDirectories(src);
        return src;
    }

    private List<SearchResult> scopedSearch(String dir) {
        return service.search(new SearchRequest("alpha", 10, null,
                SearchScope.ALL, MatchMode.FUZZY, dir));
    }

    @Test
    void shouldRejectScopedSearchOnPureOldIndex() throws Exception {
        Path index = fixtureRoot.resolve("idx-old");
        Path src = sourceDir();
        try (IndexWriter w = openWriter(index)) {
            w.addDocument(oldDoc(src.resolve("a.txt").toString(), "alpha-old.txt"));
            w.commit();
        }
        service = new LuceneSearchService(index, new IndexSettings(), () -> java.util.Collections.singletonList(src));
        SearchException ex = assertThrows(SearchException.class, () -> scopedSearch(src.toString()));
        assertEquals(SearchException.Code.INDEX_REBUILD_REQUIRED, ex.getCode());
    }

    @Test
    void shouldKeepUnscopedSearchWorkingOnOldIndex() throws Exception {
        Path index = fixtureRoot.resolve("idx-old2");
        Path src = sourceDir();
        try (IndexWriter w = openWriter(index)) {
            w.addDocument(oldDoc(src.resolve("a.txt").toString(), "alpha-old.txt"));
            w.commit();
        }
        service = new LuceneSearchService(index, new IndexSettings(), () -> java.util.Collections.singletonList(src));
        List<SearchResult> r = service.search(
                new SearchRequest("alpha-old", 10, null));
        assertEquals(1, r.size(), "unscoped must not trigger the scope gate");
    }

    @Test
    void shouldRejectMixedIndexEvenIfScopeDocsAreNew() throws Exception {
        Path index = fixtureRoot.resolve("idx-mixed");
        Path src = sourceDir();
        Path other = fixtureRoot.resolve("otherSrc");
        Files.createDirectories(other);
        try (IndexWriter w = openWriter(index)) {
            w.addDocument(newDoc(src.resolve("a.txt").toString(), "alpha-new.txt"));
            w.addDocument(oldDoc(other.resolve("b.txt").toString(), "stale.txt"));
            w.commit();
        }
        service = new LuceneSearchService(index, new IndexSettings(), () -> java.util.Collections.singletonList(src));
        // the stale doc is outside the queried scope and doesn't match the
        // query term, but the gate is over ALL live docs.
        SearchException ex = assertThrows(SearchException.class, () -> scopedSearch(src.toString()));
        assertEquals(SearchException.Code.INDEX_REBUILD_REQUIRED, ex.getCode());
    }

    @Test
    void shouldAllowScopedSearchWhenOnlyDeletedDocsLackField() throws Exception {
        Path index = fixtureRoot.resolve("idx-del");
        Path src = sourceDir();
        try (IndexWriter w = openWriter(index)) {
            w.addDocument(oldDoc(src.resolve("gone.txt").toString(), "alpha-gone.txt"));
            w.addDocument(newDoc(src.resolve("a.txt").toString(), "alpha-new.txt"));
            w.commit();
            // delete the old doc — a deleted stale doc must not block
            w.deleteDocuments(new Term("path", src.resolve("gone.txt").toString()));
            w.commit();
        }
        service = new LuceneSearchService(index, new IndexSettings(), () -> java.util.Collections.singletonList(src));
        List<SearchResult> r = scopedSearch(src.toString());
        assertEquals(1, r.size());
        assertEquals("alpha-new.txt", r.get(0).name);
    }

    @Test
    void shouldDistinguishDocCountFromNumDocs() throws Exception {
        // maxDoc=2, numDocs=1 (one deletion), docCount(path_scope)=1:
        // fast path compares getDocCount()==maxDoc → 1!=2 → falls back to
        // exact check which passes because the only live doc has the field.
        Path index = fixtureRoot.resolve("idx-del2");
        Path src = sourceDir();
        try (IndexWriter w = openWriter(index)) {
            w.addDocument(newDoc(src.resolve("gone.txt").toString(), "alpha-gone.txt"));
            w.addDocument(newDoc(src.resolve("a.txt").toString(), "alpha-new.txt"));
            w.commit();
            w.deleteDocuments(new Term("path", src.resolve("gone.txt").toString()));
            w.commit();
        }
        service = new LuceneSearchService(index, new IndexSettings(), () -> java.util.Collections.singletonList(src));
        List<SearchResult> r = scopedSearch(src.toString());
        assertEquals(1, r.size());
        assertEquals("alpha-new.txt", r.get(0).name);
    }

    @Test
    void shouldRecheckAfterReaderRefresh() throws Exception {
        // Index built fresh by real indexer → supported; then inject an old
        // doc via a raw writer → same service must re-evaluate the new
        // reader, not reuse the stale cached verdict.
        Path index = fixtureRoot.resolve("idx-refresh");
        Path src = sourceDir();
        try (IndexWriter w = openWriter(index)) {
            w.addDocument(newDoc(src.resolve("a.txt").toString(), "alpha-new.txt"));
            w.commit();
        }
        service = new LuceneSearchService(index, new IndexSettings(), () -> java.util.Collections.singletonList(src));
        assertEquals(1, scopedSearch(src.toString()).size());

        try (IndexWriter w = openWriter(index)) {
            w.addDocument(oldDoc(src.resolve("late.txt").toString(), "alpha-late.txt"));
            w.commit();
        }
        SearchException ex = assertThrows(SearchException.class, () -> scopedSearch(src.toString()));
        assertEquals(SearchException.Code.INDEX_REBUILD_REQUIRED, ex.getCode(),
                "a refreshed reader must be re-checked, not served from cache");
    }

    private static final class WrappedReader extends org.apache.lucene.index.FilterDirectoryReader {
        private final boolean nullHelper;
        private final boolean failTerms;

        WrappedReader(DirectoryReader in, boolean nullHelper, boolean failTerms) throws java.io.IOException {
            super(in, new SubReaderWrapper() {
                @Override
                public org.apache.lucene.index.LeafReader wrap(org.apache.lucene.index.LeafReader r) {
                    return new org.apache.lucene.index.FilterLeafReader(r) {
                        @Override
                        public org.apache.lucene.index.Terms terms(String field) throws java.io.IOException {
                            if (failTerms) throw new java.io.IOException("injected");
                            return super.terms(field);
                        }

                        @Override
                        public CacheHelper getCoreCacheHelper() {
                            return in.getCoreCacheHelper();
                        }

                        @Override
                        public CacheHelper getReaderCacheHelper() {
                            return in.getReaderCacheHelper();
                        }
                    };
                }
            });
            this.nullHelper = nullHelper;
            this.failTerms = failTerms;
        }

        @Override
        protected DirectoryReader doWrapDirectoryReader(DirectoryReader in) throws java.io.IOException {
            return new WrappedReader(in, nullHelper, failTerms);
        }

        @Override
        public CacheHelper getReaderCacheHelper() {
            return nullHelper ? null : in.getReaderCacheHelper();
        }
    }

    private LuceneSearchService serviceOver(Path index, Path src) throws Exception {
        service = new LuceneSearchService(index, new IndexSettings(),
                () -> java.util.Collections.singletonList(src));
        return service;
    }

    @Test
    void shouldNotCacheWhenReaderHelperIsNull() throws Exception {
        Path index = fixtureRoot.resolve("idx-nullhelper");
        Path src = sourceDir();
        try (IndexWriter w = openWriter(index)) {
            w.addDocument(oldDoc(src.resolve("a.txt").toString(), "alpha-old.txt"));
            w.commit();
        }
        serviceOver(index, src);
        try (DirectoryReader base = DirectoryReader.open(FSDirectory.open(index));
             DirectoryReader wrapped = new WrappedReader(base, true, false)) {
            assertNull(wrapped.getReaderCacheHelper());
            IndexSearcher s = new IndexSearcher(wrapped);
            for (int i = 0; i < 2; i++) {
                SearchException ex = assertThrows(SearchException.class, () -> service.checkScopeSupport(s));
                assertEquals(SearchException.Code.INDEX_REBUILD_REQUIRED, ex.getCode());
            }
            assertEquals(0, service.scopeSupportCacheSize());
        }
    }

    @Test
    void shouldNotCacheIoFailuresAndRecoverOnNextCheck() throws Exception {
        Path index = fixtureRoot.resolve("idx-iofail");
        Path src = sourceDir();
        try (IndexWriter w = openWriter(index)) {
            w.addDocument(newDoc(src.resolve("a.txt").toString(), "alpha-new.txt"));
            w.commit();
        }
        serviceOver(index, src);
        try (DirectoryReader failing = new WrappedReader(DirectoryReader.open(FSDirectory.open(index)), false, true))  {
            IndexSearcher s = new IndexSearcher(failing);
            SearchException ex = assertThrows(SearchException.class, () -> service.checkScopeSupport(s));
            assertEquals(SearchException.Code.SCOPE_CHECK_FAILED, ex.getCode());
            assertEquals(0, service.scopeSupportCacheSize(), "I/O failure must not be cached");
        }
        try (DirectoryReader base = DirectoryReader.open(FSDirectory.open(index))) {
            service.checkScopeSupport(new IndexSearcher(base));
            assertEquals(1, service.scopeSupportCacheSize());
        }
    }

    @Test
    void shouldReuseVerdictPerReaderAndEvictOnReaderClose() throws Exception {
        Path index = fixtureRoot.resolve("idx-cache");
        Path src = sourceDir();
        try (IndexWriter w = openWriter(index)) {
            w.addDocument(newDoc(src.resolve("a.txt").toString(), "alpha-new.txt"));
            w.commit();
        }
        serviceOver(index, src);
        DirectoryReader r = DirectoryReader.open(FSDirectory.open(index));
        IndexSearcher s = new IndexSearcher(r);
        service.checkScopeSupport(s);
        service.checkScopeSupport(s);
        assertEquals(1, service.scopeSupportCacheSize(), "same reader key reuses one entry");
        r.close();
        assertEquals(0, service.scopeSupportCacheSize(), "closed reader evicts its verdict");
    }

    @Test
    void shouldSurviveConcurrentRefreshAndClose() throws Exception {
        Path index = fixtureRoot.resolve("idx-concurrent");
        Path src = sourceDir();
        IndexWriter w = openWriter(index);
        w.addDocument(newDoc(src.resolve("a0.txt").toString(), "alpha-0.txt"));
        w.commit();
        serviceOver(index, src);

        java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.List<Throwable> unexpected = java.util.Collections.synchronizedList(new java.util.ArrayList<Throwable>());
        java.util.List<Thread> threads = new java.util.ArrayList<>();
        for (int t = 0; t < 4; t++) {
            Thread th = new Thread(() -> {
                while (!stop.get()) {
                    try {
                        scopedSearch(src.toString());
                    } catch (SearchException e) {
                        // acceptable only when the service is closing underneath us
                        if (!stop.get()) unexpected.add(e);
                    } catch (Throwable e) {
                        if (!stop.get()) unexpected.add(e);
                    }
                }
            });
            th.start();
            threads.add(th);
        }
        for (int i = 1; i <= 30; i++) {
            w.addDocument(newDoc(src.resolve("a" + i + ".txt").toString(), "alpha-" + i + ".txt"));
            w.commit();
            service.notifyIndexCommit();
        }
        stop.set(true);
        for (Thread th : threads) th.join(10000);
        w.close();
        assertTrue(unexpected.isEmpty(), "unexpected failures: " + unexpected);
        assertTrue(service.scopeSupportCacheSize() <= 2, "stale reader verdicts must be evicted");
        service.close();
        assertEquals(0, service.scopeSupportCacheSize());
    }

    @Test
    void shouldPassEmptyLiveReader() throws Exception {
        Path index = fixtureRoot.resolve("idx-empty");
        Path src = sourceDir();
        try (IndexWriter w = openWriter(index)) {
            w.addDocument(newDoc(src.resolve("a.txt").toString(), "alpha-new.txt"));
            w.commit();
            w.deleteAll();
            w.commit();
        }
        service = new LuceneSearchService(index, new IndexSettings(), () -> java.util.Collections.singletonList(src));
        assertTrue(scopedSearch(src.toString()).isEmpty(),
                "empty live reader passes the gate and returns zero hits");
    }
}
