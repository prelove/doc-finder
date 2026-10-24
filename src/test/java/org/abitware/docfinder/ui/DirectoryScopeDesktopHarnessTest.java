package org.abitware.docfinder.ui;

import org.abitware.docfinder.index.IndexSettings;
import org.abitware.docfinder.search.SearchException;
import org.abitware.docfinder.search.SearchErrorMessages;
import org.abitware.docfinder.search.SearchRequest;
import org.abitware.docfinder.search.SearchResult;
import org.abitware.docfinder.search.SearchService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import javax.swing.DefaultComboBoxModel;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A5a harness: drives the real MainWindow (constructed, never shown) with
 * fake services and latch-controlled openSearchService to verify the D3/D8
 * timing contracts — null-service worker race, Code-mapped status text,
 * stale-candidate close, error not recorded in history.
 * Requires a display; skipped automatically on headless CI.
 */
@Execution(ExecutionMode.SAME_THREAD)
class DirectoryScopeDesktopHarnessTest {

    /** Fake service whose search() can block or throw on demand. */
    private static final class FakeService implements SearchService {
        final AtomicBoolean closed = new AtomicBoolean();
        volatile CountDownLatch blockOn;
        volatile CountDownLatch entered;
        volatile CountDownLatch finished;
        volatile RuntimeException throwOnSearch;
        volatile List<SearchResult> canned = Collections.emptyList();

        @Override
        public List<SearchResult> search(SearchRequest request) {
            if (entered != null) {
                entered.countDown();
            }
            // Keep blocking even when the worker is cancelled — we are
            // simulating a service mid-call, and a cancel interrupt must not
            // release the executor before the test orchestrates it.
            while (blockOn != null && blockOn.getCount() > 0) {
                try {
                    blockOn.await(1, TimeUnit.SECONDS);
                } catch (InterruptedException ignore) {
                }
            }
            if (throwOnSearch != null) {
                throw throwOnSearch;
            }
            if (finished != null) {
                finished.countDown();
            }
            return canned;
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }

    /** MainWindow subclass with a latch-controllable service factory. */
    private static final class HarnessWindow extends MainWindow {
        volatile CountDownLatch gate;
        volatile RuntimeException openFailure;
        volatile SearchService firstService;
        volatile SearchService nextService;
        volatile int openCalls;

        HarnessWindow(SearchService svc) {
            super(svc);
        }

        @Override
        protected SearchService openSearchService(Path indexDir, IndexSettings settings)
                throws java.io.IOException {
            int call = ++openCalls;
            try {
                if (gate != null) {
                    gate.await(15, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (openFailure != null) {
                throw new java.io.IOException("injected open failure", openFailure);
            }
            // The service a candidate returns must be decided at call time —
            // a blocked open that resumes later must not pick up a newer
            // request's service.
            return (call == 1 && firstService != null) ? firstService : nextService;
        }
    }

    private HarnessWindow win;

    @BeforeEach
    void checkDisplay() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (win != null) {
            SwingUtilities.invokeAndWait(() -> win.dispose());
        }
    }

    private static void onEdt(Runnable r) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            SwingUtilities.invokeAndWait(r);
        }
    }

    /** Poll the status label (EDT) until it shows expected or timeout. */
    private boolean waitForStatus(String expected, long ms) throws Exception {
        long deadline = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < deadline) {
            AtomicReference<String> cur = new AtomicReference<>();
            onEdt(() -> cur.set(win.getStatusLabel().getText()));
            if (expected.equals(cur.get())) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    private static void invokeDoSearch(MainWindow w) throws Exception {
        Method m = MainWindow.class.getDeclaredMethod("doSearch");
        m.setAccessible(true);
        onEdt(() -> {
            try {
                m.invoke(w);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private void setQuery(MainWindow w, String q) throws Exception {
        onEdt(() -> w.getSearchField().setText(q));
    }

    private static String expectedMsg(SearchException.Code c) {
        return SearchErrorMessages.forCode(c);
    }

    @Test
    void shouldMapEveryCodeToFixedStatusText() throws Exception {
        FakeService fake = new FakeService();
        win = new HarnessWindow(fake);
        for (SearchException.Code code : SearchException.Code.values()) {
            fake.throwOnSearch = new SearchException(code, "raw " + code + " secret-detail");
            setQuery(win, "alpha");
            invokeDoSearch(win);
            assertTrue(waitForStatus(expectedMsg(code), 10_000),
                    "status must show fixed text for " + code);
            String shown = win.getStatusLabel().getText();
            assertFalse(shown.contains("secret-detail"), "no raw message leak for " + code);
        }
        // unknown exception → SEARCH_FAILED text
        fake.throwOnSearch = new RuntimeException("boom raw");
        invokeDoSearch(win);
        assertTrue(waitForStatus(expectedMsg(SearchException.Code.SEARCH_FAILED), 10_000));
        assertFalse(win.getStatusLabel().getText().contains("boom"));
    }

    @Test
    void shouldShowIndexUnavailableNotNoResultsWhenServiceTurnsNull() throws Exception {
        // Worker 1 blocks inside a fake service; while it occupies the single
        // executor, worker 2 is queued and the service is nulled — worker 2
        // must observe null and surface INDEX_UNAVAILABLE, never "No results".
        FakeService blocker = new FakeService();
        blocker.blockOn = new CountDownLatch(1);
        blocker.entered = new CountDownLatch(1);
        blocker.canned = Collections.singletonList(
                new SearchResult("x", "p", 1f, 0L, 0L, "name", 1L, false));
        win = new HarnessWindow(blocker);

        setQuery(win, "alpha");
        invokeDoSearch(win);          // worker1 enters fake.search and blocks
        assertTrue(blocker.entered.await(10, TimeUnit.SECONDS),
                "worker1 must be inside the blocking service");
        invokeDoSearch(win);          // worker2 queued behind worker1

        onEdt(() -> win.setSearchService(null)); // service goes away mid-flight
        blocker.blockOn.countDown();             // release worker1

        assertTrue(waitForStatus(expectedMsg(SearchException.Code.INDEX_UNAVAILABLE), 10_000));
        assertFalse(win.getStatusLabel().getText().startsWith("No results"),
                "rebuild/unavailable period must not look like zero hits");
    }

    @Test
    void shouldNotAddFailedQueryToHistory() throws Exception {
        FakeService fake = new FakeService();
        fake.throwOnSearch = new SearchException(
                SearchException.Code.INVALID_QUERY, "parse err");
        win = new HarnessWindow(fake);
        int before = ((DefaultComboBoxModel<String>)
                getQueryModel(win)).getSize();
        setQuery(win, "report AND (");
        invokeDoSearch(win);
        assertTrue(waitForStatus(expectedMsg(SearchException.Code.INVALID_QUERY), 10_000));
        int after = ((DefaultComboBoxModel<String>) getQueryModel(win)).getSize();
        assertEquals(before, after, "failed query must not enter history");
    }

    @SuppressWarnings("unchecked")
    private DefaultComboBoxModel<String> getQueryModel(MainWindow w) {
        // The public surface does not expose queryBox; reach it via the
        // searchField's parent combo? Simpler: reflection.
        try {
            java.lang.reflect.Field f = MainWindow.class.getDeclaredField("queryBox");
            f.setAccessible(true);
            javax.swing.JComboBox<String> box = (javax.swing.JComboBox<String>) f.get(w);
            return (DefaultComboBoxModel<String>) box.getModel();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void shouldCloseStaleOpenCandidateAndKeepNewest() throws Exception {
        win = new HarnessWindow(null);
        CountDownLatch firstGate = new CountDownLatch(1);
        FakeService first = new FakeService();
        FakeService second = new FakeService();

        // first open: gated
        win.gate = firstGate;
        win.firstService = first;
        win.reopenSearchServiceAsync(java.nio.file.Paths.get("idx"), new IndexSettings());
        Thread.sleep(300); // ensure first open is in progress
        win.gate = null;

        // second open supersedes the first
        win.nextService = second;
        win.reopenSearchServiceAsync(java.nio.file.Paths.get("idx"), new IndexSettings());
        Thread.sleep(300);
        firstGate.countDown(); // first candidate finally resolves — too late

        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline
                && (win.getCurrentSearchService() != second || !first.closed.get())) {
            Thread.sleep(50);
        }
        assertSame(second, win.getCurrentSearchService(), "newest open wins");
        assertTrue(first.closed.get(), "late candidate must be closed, never published");
        assertFalse(second.closed.get());
    }

    @Test
    void shouldKeepFailureStatusAfterPartialRecoveryPublish() throws Exception {
        win = new HarnessWindow(null);
        FakeService svc = new FakeService();
        win.nextService = svc;
        String partial = "Indexing failed or partially completed."
                + " Search uses available committed data. See the application log.";
        win.reopenSearchServiceAsync(java.nio.file.Paths.get("idx"), new IndexSettings(), partial);
        assertTrue(waitForStatus(partial, 10_000),
                "failure status must survive the publish — no bare 'Ready'");
        assertSame(svc, win.getCurrentSearchService());
    }

    @Test
    void shouldInvalidateCompletedSearchWhenEmptyQueryIsSubmittedBeforeDone() throws Exception {
        FakeService fake = new FakeService();
        fake.finished = new CountDownLatch(1);
        fake.canned = Collections.singletonList(
                new SearchResult("late.txt", "p/late.txt", 1f, 0L, 0L, "name", 1L, false));
        win = new HarnessWindow(fake);

        // Worker finishes in the background while this EDT task still runs,
        // so its done() is queued behind us; the empty submit must invalidate it.
        onEdt(() -> {
            try {
                win.getSearchField().setText("alpha");
                invokeDoSearch(win);
                assertTrue(fake.finished.await(10, TimeUnit.SECONDS));
                win.getSearchField().setText("");
                invokeDoSearch(win);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        Thread.sleep(500);
        onEdt(() -> { });

        assertEquals(0, win.getResultTable().getRowCount(), "late result must not repopulate the table");
        assertEquals("Enter a query to search.", win.getStatusLabel().getText());
    }

    @Test
    void shouldIgnoreLateSearchResultAfterReopenFailure() throws Exception {
        // R24-01: an in-flight query must be invalidated when the reopen
        // FAILS — a late success from the old index must not repopulate
        // rows/selection/preview or overwrite the unavailable status.
        FakeService old = new FakeService();
        old.canned = Collections.singletonList(
                new SearchResult("late-old.txt", "p/late-old.txt", 1f, 0L, 0L, "name", 1L, false));
        old.entered = new CountDownLatch(1);
        old.finished = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        old.blockOn = release;
        win = new HarnessWindow(old);
        // Observe the web-side publication too: a failed open must push null.
        AtomicReference<SearchService> webSvc = new AtomicReference<>();
        win.setWebServer(new org.abitware.docfinder.web.WebServer(0, "127.0.0.1") {
            @Override
            public void setSearchService(SearchService svc) {
                webSvc.set(svc);
                super.setSearchService(svc);
            }
        });
        try {
            setQuery(win, "alpha");
            invokeDoSearch(win);
            assertTrue(old.entered.await(10, TimeUnit.SECONDS),
                    "old query must be in-flight before the reopen fails");
            win.openFailure = new RuntimeException("injected new index unavailable");
            win.reopenSearchServiceAsync(java.nio.file.Paths.get("another-index"), new IndexSettings());
            String unavailable = expectedMsg(SearchException.Code.INDEX_UNAVAILABLE);
            assertTrue(waitForStatus(unavailable, 10_000));
            assertNull(win.getCurrentSearchService());

            release.countDown();
            assertTrue(old.finished.await(10, TimeUnit.SECONDS));
            // let the stale done() run on the EDT, then flush pending EDT work
            onEdt(() -> { });
            Thread.sleep(300);
            onEdt(() -> { });

            assertEquals(0, win.getResultTable().getRowCount(),
                    "late success from old index must not repopulate results");
            assertEquals(unavailable, win.getStatusLabel().getText(),
                    "unavailable status must not be overwritten by the late worker");
            assertNull(win.getCurrentSearchService());
            assertNull(webSvc.get(), "web server must receive the null publication");
        } finally {
            release.countDown();
        }
    }

    @Test
    void shouldIgnoreLateSearchErrorAfterReopenFailure() throws Exception {
        // R24-01 (error variant): a late failure from the old index must also
        // be invalidated — its fixed error text must not replace the
        // unavailable status.
        FakeService old = new FakeService();
        old.entered = new CountDownLatch(1);
        old.finished = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        old.blockOn = release;
        win = new HarnessWindow(old);
        try {
            setQuery(win, "alpha");
            invokeDoSearch(win);
            assertTrue(old.entered.await(10, TimeUnit.SECONDS));
            win.openFailure = new RuntimeException("injected new index unavailable");
            win.reopenSearchServiceAsync(java.nio.file.Paths.get("another-index"), new IndexSettings());
            String unavailable = expectedMsg(SearchException.Code.INDEX_UNAVAILABLE);
            assertTrue(waitForStatus(unavailable, 10_000));
            assertNull(win.getCurrentSearchService());

            old.throwOnSearch = new org.abitware.docfinder.search.SearchException(
                    SearchException.Code.SEARCH_FAILED, "old index blew up late");
            release.countDown();
            // the thrown error never reaches finished.countDown() — give the
            // worker time, then flush the EDT so done() has run
            Thread.sleep(500);
            onEdt(() -> { });
            Thread.sleep(300);
            onEdt(() -> { });

            assertEquals(0, win.getResultTable().getRowCount(),
                    "late error must not repopulate results");
            assertEquals(unavailable, win.getStatusLabel().getText(),
                    "unavailable status must survive the late error text");
            assertNull(win.getCurrentSearchService());
        } finally {
            release.countDown();
        }
    }

    @Test
    void shouldRunRealIndexAllLifecycleAndPublishService() throws Exception {
        // QA24-06/R24-02: exercise the ACTUAL indexAllSources entry (not the
        // isIndexing flag alone): real sources.txt + config.properties in the
        // isolated .docfinder base, real LuceneIndexer, real done() reopen
        // path — publishing goes through the harness open hook.
        //
        // The index dir MUST be written via ConfigManager.setIndexDir
        // (Properties.store escapes Windows backslashes correctly); a raw
        // "index.dir=" + path line gets mangled by Properties.load and
        // silently redirects the real index outside the test root.
        Path base = org.abitware.docfinder.util.AppPaths.getBaseDir();
        java.nio.file.Files.createDirectories(base);
        Path tmp = java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"))
                .toAbsolutePath().normalize();
        Path src = tmp.resolve("qa-indexall-src-" + System.nanoTime());
        java.nio.file.Files.createDirectories(src.resolve("child"));
        java.nio.file.Files.write(src.resolve("f1.txt"), "alpha one".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        java.nio.file.Files.write(src.resolve("child").resolve("f2.txt"), "alpha two".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Path idx = tmp.resolve("qa-indexall-idx-" + System.nanoTime());

        // Preserve any pre-existing config/sources content byte-for-byte.
        Path sourcesFile = base.resolve("sources.txt");
        Path configFile = base.resolve("config.properties");
        byte[] prevSources = java.nio.file.Files.exists(sourcesFile)
                ? java.nio.file.Files.readAllBytes(sourcesFile) : null;
        byte[] prevConfig = java.nio.file.Files.exists(configFile)
                ? java.nio.file.Files.readAllBytes(configFile) : null;
        try {
            java.nio.file.Files.write(sourcesFile,
                    (src.toAbsolutePath() + "|0").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            org.abitware.docfinder.index.ConfigManager cm =
                    new org.abitware.docfinder.index.ConfigManager();
            cm.setIndexDir(idx);

            // R24-02 guard: BEFORE any indexing, the configuration must read
            // back the exact intended index dir, and that dir must live
            // inside the allowed QA temp root — fail before a single index
            // byte can be written elsewhere.
            Path readback = cm.getIndexDir();
            assertEquals(idx.toAbsolutePath().normalize(), readback,
                    "index.dir must round-trip exactly through Properties.store/load");
            assertTrue(readback.startsWith(tmp),
                    "index dir must stay inside the temp root: " + readback);

            win = new HarnessWindow(null);
            FakeService opened = new FakeService();
            win.nextService = opened;
            onEdt(() -> win.onIndexAllSources());

            long deadline = System.currentTimeMillis() + 20_000;
            while (System.currentTimeMillis() < deadline && win.getCurrentSearchService() == null) {
                Thread.sleep(100);
            }
            assertSame(opened, win.getCurrentSearchService(),
                    "real indexAllSources lifecycle must publish a reopened service");
            AtomicReference<String> status = new AtomicReference<>();
            onEdt(() -> status.set(win.getStatusLabel().getText()));
            // setSearchService() ends a successful publish at "Ready".
            assertEquals("Ready", status.get(),
                    "post-publish status after real Index All: " + status.get());
            java.lang.reflect.Field indexing = MainWindow.class.getDeclaredField("isIndexing");
            indexing.setAccessible(true);
            assertFalse(indexing.getBoolean(win), "isIndexing cleared at lifecycle end");

            // R24-02/03: the REAL index at the EXACT configured location must
            // contain the two indexed files — not a fake publish, not a
            // redirected directory.
            assertTrue(java.nio.file.Files.exists(idx),
                    "index must exist at the configured path");
            try (org.apache.lucene.index.IndexReader r =
                    org.apache.lucene.index.DirectoryReader.open(
                            org.apache.lucene.store.FSDirectory.open(idx))) {
                java.util.Set<String> paths = collectPaths(r);
                // the index also contains docs for the source dir and its
                // child dir — assert membership, not a raw doc count
                assertTrue(paths.contains(src.resolve("f1.txt").toString()), paths.toString());
                assertTrue(paths.contains(src.resolve("child").resolve("f2.txt").toString()), paths.toString());
                assertTrue(paths.stream().allMatch(p -> p != null && p.startsWith(src.toString())),
                        "index must contain only docs under the test source: " + paths);
            }
        } finally {
            if (prevSources == null) {
                java.nio.file.Files.deleteIfExists(sourcesFile);
            } else {
                java.nio.file.Files.write(sourcesFile, prevSources);
            }
            if (prevConfig == null) {
                java.nio.file.Files.deleteIfExists(configFile);
            } else {
                java.nio.file.Files.write(configFile, prevConfig);
            }
            deleteRecursively(src);
            deleteRecursively(idx);
        }
    }

    @Test
    void shouldInvalidateInFlightSearchDuringRealIndexAll() throws Exception {
        // R24-03: the REAL indexAllSources entry must invalidate an in-flight
        // query started before the rebuild — its late success must not
        // repopulate the table once indexing publishes a fresh service.
        Path base = org.abitware.docfinder.util.AppPaths.getBaseDir();
        java.nio.file.Files.createDirectories(base);
        Path tmp = java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"))
                .toAbsolutePath().normalize();
        Path src = tmp.resolve("qa-indexall-src-" + System.nanoTime());
        java.nio.file.Files.createDirectories(src.resolve("child"));
        java.nio.file.Files.write(src.resolve("f1.txt"), "alpha one".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        java.nio.file.Files.write(src.resolve("child").resolve("f2.txt"), "alpha two".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Path idx = tmp.resolve("qa-indexall-idx-" + System.nanoTime());

        Path sourcesFile = base.resolve("sources.txt");
        Path configFile = base.resolve("config.properties");
        byte[] prevSources = java.nio.file.Files.exists(sourcesFile)
                ? java.nio.file.Files.readAllBytes(sourcesFile) : null;
        byte[] prevConfig = java.nio.file.Files.exists(configFile)
                ? java.nio.file.Files.readAllBytes(configFile) : null;

        CountDownLatch release = new CountDownLatch(1);
        try {
            java.nio.file.Files.write(sourcesFile,
                    (src.toAbsolutePath() + "|0").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            org.abitware.docfinder.index.ConfigManager cm =
                    new org.abitware.docfinder.index.ConfigManager();
            cm.setIndexDir(idx);
            Path readback = cm.getIndexDir();
            assertEquals(idx.toAbsolutePath().normalize(), readback);
            assertTrue(readback.startsWith(tmp), "index dir outside QA root: " + readback);

            FakeService old = new FakeService();
            old.canned = Collections.singletonList(
                    new SearchResult("old.txt", "p/old.txt", 1f, 0L, 0L, "name", 1L, false));
            old.entered = new CountDownLatch(1);
            old.finished = new CountDownLatch(1);
            old.blockOn = release;
            win = new HarnessWindow(old);
            setQuery(win, "alpha");
            invokeDoSearch(win);
            assertTrue(old.entered.await(10, TimeUnit.SECONDS),
                    "old query must be in-flight before Index All starts");

            FakeService opened = new FakeService();
            win.nextService = opened;
            onEdt(() -> win.onIndexAllSources());

            long deadline = System.currentTimeMillis() + 20_000;
            while (System.currentTimeMillis() < deadline && win.getCurrentSearchService() != opened) {
                Thread.sleep(100);
            }
            assertSame(opened, win.getCurrentSearchService(),
                    "real Index All must publish a service");
            release.countDown();
            assertTrue(old.finished.await(10, TimeUnit.SECONDS));
            onEdt(() -> { });
            Thread.sleep(300);
            onEdt(() -> { });

            assertEquals(0, win.getResultTable().getRowCount(),
                    "query in-flight across Index All must not repopulate results");
            AtomicReference<String> status = new AtomicReference<>();
            onEdt(() -> status.set(win.getStatusLabel().getText()));
            assertEquals("Ready", status.get(), "post-Index-All status: " + status.get());
            try (org.apache.lucene.index.IndexReader r =
                    org.apache.lucene.index.DirectoryReader.open(
                            org.apache.lucene.store.FSDirectory.open(idx))) {
                java.util.Set<String> paths = collectPaths(r);
                assertTrue(paths.contains(src.resolve("f1.txt").toString()), paths.toString());
                assertTrue(paths.contains(src.resolve("child").resolve("f2.txt").toString()), paths.toString());
                assertTrue(paths.stream().allMatch(p -> p != null && p.startsWith(src.toString())),
                        "index must contain only docs under the test source: " + paths);
            }
        } finally {
            release.countDown();
            if (prevSources == null) {
                java.nio.file.Files.deleteIfExists(sourcesFile);
            } else {
                java.nio.file.Files.write(sourcesFile, prevSources);
            }
            if (prevConfig == null) {
                java.nio.file.Files.deleteIfExists(configFile);
            } else {
                java.nio.file.Files.write(configFile, prevConfig);
            }
            deleteRecursively(src);
            deleteRecursively(idx);
        }
    }

    @Test
    void shouldKeepPartialStatusAfterRealIndexAllSecondSourceCommitFails() throws Exception {
        // R24-03: REAL indexAllSources entry; source A commits, then the
        // index dir is made unwritable before source B's commit — the second
        // commit REALLY fails. The partial-failure text must survive publish
        // and the committed index must contain A but no B documents.
        Path base = org.abitware.docfinder.util.AppPaths.getBaseDir();
        java.nio.file.Files.createDirectories(base);
        Path tmp = java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"))
                .toAbsolutePath().normalize();
        Path srcA = tmp.resolve("qa-partial-src-a-" + System.nanoTime());
        Path srcB = tmp.resolve("qa-partial-src-b-" + System.nanoTime());
        java.nio.file.Files.createDirectories(srcA);
        java.nio.file.Files.createDirectories(srcB);
        java.nio.file.Files.write(srcA.resolve("a1.txt"),
                "alpha committed a".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        // Enough files that walking B comfortably outlives the ACL watchdog.
        for (int i = 0; i < 200; i++) {
            java.nio.file.Files.write(srcB.resolve("b" + i + ".txt"),
                    ("alpha uncommitted b " + i).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        Path idx = tmp.resolve("qa-partial-idx-" + System.nanoTime());

        Path sourcesFile = base.resolve("sources.txt");
        Path configFile = base.resolve("config.properties");
        byte[] prevSources = java.nio.file.Files.exists(sourcesFile)
                ? java.nio.file.Files.readAllBytes(sourcesFile) : null;
        byte[] prevConfig = java.nio.file.Files.exists(configFile)
                ? java.nio.file.Files.readAllBytes(configFile) : null;

        String partial = "Indexing failed or partially completed."
                + " Search uses available committed data. See the application log.";
        AtomicReference<java.util.List<java.nio.file.attribute.AclEntry>> originalAcl =
                new AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean denyApplied =
                new java.util.concurrent.atomic.AtomicBoolean();
        Thread watchdog = new Thread(() -> {
            try {
                // Source A's commit is complete once a committed segments file
                // exists; deny writes so source B's commit really fails.
                long until = System.currentTimeMillis() + 15_000;
                while (System.currentTimeMillis() < until && java.nio.file.Files.exists(idx)) {
                    try (java.util.stream.Stream<Path> s = java.nio.file.Files.list(idx)) {
                        if (s.anyMatch(f -> f.getFileName().toString().startsWith("segments_"))) {
                            java.util.List<java.nio.file.attribute.AclEntry> orig = denyWrite(idx);
                            originalAcl.set(orig);
                            denyApplied.set(orig != null); // null = ACL view unsupported
                            return;
                        }
                    }
                    Thread.sleep(2);
                }
            } catch (Exception ignore) {
            }
        }, "qa-acl-watchdog");
        try {
            java.nio.file.Files.write(sourcesFile,
                    ((srcA.toAbsolutePath() + "|0\n" + srcB.toAbsolutePath() + "|0")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            org.abitware.docfinder.index.ConfigManager cm =
                    new org.abitware.docfinder.index.ConfigManager();
            cm.setIndexDir(idx);
            Path readback = cm.getIndexDir();
            assertEquals(idx.toAbsolutePath().normalize(), readback);
            assertTrue(readback.startsWith(tmp), "index dir outside QA root: " + readback);
            java.nio.file.Files.createDirectories(idx);
            assumeTrue(java.nio.file.Files.getFileAttributeView(idx,
                            java.nio.file.attribute.AclFileAttributeView.class) != null,
                    "ACL view required for real commit-failure injection");

            win = new HarnessWindow(null);
            win.nextService = new FakeService();
            watchdog.start();
            onEdt(() -> win.onIndexAllSources());

            assertTrue(waitForStatus(partial, 30_000),
                    "partial-failure status must survive publish — no bare Ready");

            // The failure status is set before the async reopen finishes —
            // poll for the actual publish.
            long deadline = System.currentTimeMillis() + 15_000;
            while (System.currentTimeMillis() < deadline && win.getCurrentSearchService() == null) {
                Thread.sleep(50);
            }
            java.lang.reflect.Field indexing = MainWindow.class.getDeclaredField("isIndexing");
            indexing.setAccessible(true);
            assertFalse(indexing.getBoolean(win), "isIndexing cleared after partial failure");
            assertSame(win.nextService, win.getCurrentSearchService(),
                    "committed data service must still be republished");
            assertTrue(denyApplied.get(), "ACL deny must have landed — else the failure was fake");

            // Real index: A committed, B never committed — verify at reader
            // level, not via a scope-filtered query that hides B anyway.
            try (org.apache.lucene.index.IndexReader r =
                    org.apache.lucene.index.DirectoryReader.open(
                            org.apache.lucene.store.FSDirectory.open(idx))) {
                java.util.Set<String> paths = collectPaths(r);
                assertTrue(paths.contains(srcA.resolve("a1.txt").toAbsolutePath().toString())
                        || paths.stream().anyMatch(p -> p != null && p.startsWith(srcA.toString())),
                        "committed source A doc must be present: " + paths);
                assertFalse(paths.stream().anyMatch(p -> p != null && p.startsWith(srcB.toString())),
                        "uncommitted source B docs must be absent: " + paths);
            }
        } finally {
            if (originalAcl.get() != null) {
                restoreAcl(idx, originalAcl.get());
            }
            if (prevSources == null) {
                java.nio.file.Files.deleteIfExists(sourcesFile);
            } else {
                java.nio.file.Files.write(sourcesFile, prevSources);
            }
            if (prevConfig == null) {
                java.nio.file.Files.deleteIfExists(configFile);
            } else {
                java.nio.file.Files.write(configFile, prevConfig);
            }
            deleteRecursively(srcA);
            deleteRecursively(srcB);
            deleteRecursively(idx);
        }
    }

    private static java.util.List<java.nio.file.attribute.AclEntry> denyWrite(Path dir)
            throws java.io.IOException {
        java.nio.file.attribute.AclFileAttributeView view = java.nio.file.Files.getFileAttributeView(
                dir, java.nio.file.attribute.AclFileAttributeView.class);
        if (view == null) {
            return null;
        }
        java.nio.file.attribute.UserPrincipal me = java.nio.file.FileSystems.getDefault()
                .getUserPrincipalLookupService()
                .lookupPrincipalByName(System.getProperty("user.name"));
        java.util.List<java.nio.file.attribute.AclEntry> original =
                new java.util.ArrayList<>(view.getAcl());
        java.util.List<java.nio.file.attribute.AclEntry> denied =
                new java.util.ArrayList<>(original);
        denied.add(0, java.nio.file.attribute.AclEntry.newBuilder()
                .setType(java.nio.file.attribute.AclEntryType.DENY)
                .setPrincipal(me)
                .setPermissions(java.nio.file.attribute.AclEntryPermission.WRITE_DATA,
                        java.nio.file.attribute.AclEntryPermission.ADD_FILE,
                        java.nio.file.attribute.AclEntryPermission.ADD_SUBDIRECTORY,
                        java.nio.file.attribute.AclEntryPermission.WRITE_ATTRIBUTES,
                        java.nio.file.attribute.AclEntryPermission.DELETE)
                .build());
        view.setAcl(denied);
        return original;
    }

    private static void restoreAcl(Path dir,
            java.util.List<java.nio.file.attribute.AclEntry> original) {
        try {
            java.nio.file.Files.getFileAttributeView(dir, java.nio.file.attribute.AclFileAttributeView.class)
                    .setAcl(original);
        } catch (java.io.IOException ignore) {
        }
    }

    private static java.util.Set<String> collectPaths(org.apache.lucene.index.IndexReader r)
            throws java.io.IOException {
        java.util.Set<String> paths = new java.util.HashSet<>();
        for (int i = 0; i < r.maxDoc(); i++) {
            org.apache.lucene.document.Document d = r.document(i);
            if (d != null) {
                paths.add(d.get("path"));
            }
        }
        return paths;
    }

    private static void deleteRecursively(Path root) {
        if (root == null || !java.nio.file.Files.exists(root)) {
            return;
        }
        try {
            java.nio.file.Files.walkFileTree(root,
                    new java.nio.file.SimpleFileVisitor<Path>() {
                        @Override
                        public java.nio.file.FileVisitResult visitFile(Path f,
                                java.nio.file.attribute.BasicFileAttributes a)
                                throws java.io.IOException {
                            java.nio.file.Files.deleteIfExists(f);
                            return java.nio.file.FileVisitResult.CONTINUE;
                        }

                        @Override
                        public java.nio.file.FileVisitResult postVisitDirectory(Path d,
                                java.io.IOException e) throws java.io.IOException {
                            if (e != null) {
                                throw e;
                            }
                            java.nio.file.Files.deleteIfExists(d);
                            return java.nio.file.FileVisitResult.CONTINUE;
                        }
                    });
        } catch (java.io.IOException ignore) {
        }
    }

    @Test
    void shouldInvalidateCompletedSearchWhenIndexingStartsBeforeDone() throws Exception {
        FakeService fake = new FakeService();
        fake.finished = new CountDownLatch(1);
        fake.canned = Collections.singletonList(
                new SearchResult("late.txt", "p/late.txt", 1f, 0L, 0L, "name", 1L, false));
        win = new HarnessWindow(fake);
        java.lang.reflect.Field indexing = MainWindow.class.getDeclaredField("isIndexing");
        indexing.setAccessible(true);

        onEdt(() -> {
            try {
                win.getSearchField().setText("alpha");
                invokeDoSearch(win);
                assertTrue(fake.finished.await(10, TimeUnit.SECONDS));
                indexing.setBoolean(win, true);
                invokeDoSearch(win);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        Thread.sleep(500);
        onEdt(() -> { });

        assertEquals(0, win.getResultTable().getRowCount(), "stale result must not publish while indexing");
    }

    @Test
    void shouldLockFiltersAndScopeControlsWhileIndexing() throws Exception {
        win = new HarnessWindow(new FakeService());
        Method lock = MainWindow.class.getDeclaredMethod("setIndexingUiEnabled", boolean.class);
        lock.setAccessible(true);
        String[] names = { "toggleFiltersBtn", "applyFiltersBtn", "extField", "fromField", "toField" };
        for (boolean enabled : new boolean[] { false, true }) {
            onEdt(() -> {
                try {
                    lock.invoke(win, enabled);
                    for (String n : names) {
                        java.lang.reflect.Field f = MainWindow.class.getDeclaredField(n);
                        f.setAccessible(true);
                        assertEquals(enabled, ((java.awt.Component) f.get(win)).isEnabled(), n);
                    }
                    assertEquals(enabled, win.getDirectoryScopePanel().getSourceCombo().isEnabled());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    @Test
    void shouldSyncWebServerWhenLockRecoveryStopsService() throws Exception {
        FakeService svc = new FakeService();
        win = new HarnessWindow(null);
        org.abitware.docfinder.web.WebServer web =
                new org.abitware.docfinder.web.WebServer(0, "127.0.0.1");
        win.setWebServer(web);
        // Publish through setSearchService so the web server holds the same
        // reference — the defect under test is that lock recovery used to
        // clear only the desktop field and leave the web side stale.
        onEdt(() -> win.setSearchService(svc));
        java.lang.reflect.Field f =
                org.abitware.docfinder.web.WebServer.class.getDeclaredField("searchService");
        f.setAccessible(true);
        assertSame(svc, f.get(web), "setup: web must hold the published service");
        // Production calls this from a background thread before deleting index
        // files; invoking off-EDT exercises the invokeAndWait path and proves
        // the close still completes synchronously without deadlock.
        Method m = MainWindow.class.getDeclaredMethod("stopIndexServicesForLockRecovery");
        m.setAccessible(true);
        Thread t = new Thread(() -> {
            try {
                m.invoke(win);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        t.start();
        t.join(10_000);
        assertFalse(t.isAlive(), "lock recovery must not deadlock off-EDT");

        assertTrue(svc.closed.get(), "old service must be closed");
        assertNull(win.getCurrentSearchService(), "service must be unpublished");
        assertNull(f.get(web), "web server must observe INDEX_UNAVAILABLE, not a stale service");
    }

    @Test
    void shouldInvalidateCompletedSearchWhenFullRebuildFailsBeforeDone() throws Exception {
        FakeService fake = new FakeService();
        fake.finished = new CountDownLatch(1);
        fake.canned = Collections.singletonList(
                new SearchResult("late.txt", "p/late.txt", 1f, 0L, 0L, "name", 1L, false));
        win = new HarnessWindow(fake);

        // Worker finished in the background but its done() is still queued on
        // the EDT; a rebuild failure must invalidate it before it publishes.
        onEdt(() -> {
            try {
                win.getSearchField().setText("alpha");
                invokeDoSearch(win);
                assertTrue(fake.finished.await(10, TimeUnit.SECONDS));
                win.handleFullRebuildFailure();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        Thread.sleep(500);
        onEdt(() -> { });

        assertEquals(0, win.getResultTable().getRowCount(), "late result must not publish after rebuild failure");
        assertEquals("Rebuild failed. Search is unavailable until the index is rebuilt. See the application log.",
                win.getStatusLabel().getText());
        assertNull(win.getCurrentSearchService());
    }

    @Test
    void shouldClearPresentedResultsWhenServiceBecomesUnavailable() throws Exception {
        FakeService fake = new FakeService();
        fake.canned = Collections.singletonList(
                new SearchResult("old.txt", "p/old.txt", 1f, 0L, 0L, "name", 1L, false));
        win = new HarnessWindow(fake);
        setQuery(win, "alpha");
        invokeDoSearch(win);
        assertTrue(waitForStatusPrefix("Results:", 10_000));
        assertEquals(1, win.getResultTable().getRowCount());

        onEdt(() -> win.setSearchService(null));
        invokeDoSearch(win);

        assertEquals(0, win.getResultTable().getRowCount(),
                "unavailable search must clear earlier successful results");
    }

    @Test
    void shouldClearPresentedResultsWhenFullRebuildFails() throws Exception {
        FakeService fake = new FakeService();
        fake.canned = Collections.singletonList(
                new SearchResult("old.txt", "p/old.txt", 1f, 0L, 0L, "name", 1L, false));
        win = new HarnessWindow(fake);
        setQuery(win, "alpha");
        invokeDoSearch(win);
        assertTrue(waitForStatusPrefix("Results:", 10_000));
        assertEquals(1, win.getResultTable().getRowCount());

        onEdt(() -> win.handleFullRebuildFailure());

        assertEquals(0, win.getResultTable().getRowCount(),
                "rebuild failure must clear already rendered results");
    }

    @Test
    void shouldNotSilentlyKeepOldServiceWhenOpeningNewLocationFails() throws Exception {
        FakeService old = new FakeService();
        win = new HarnessWindow(old);
        win.openFailure = new RuntimeException("new location cannot open");
        win.reopenSearchServiceAsync(java.nio.file.Paths.get("other-index"), new IndexSettings());

        assertTrue(waitForStatus(expectedMsg(SearchException.Code.INDEX_UNAVAILABLE), 10_000));
        assertNull(win.getCurrentSearchService(),
                "unavailable new location must not leave old service active");
        assertTrue(old.closed.get(), "the stale service must be closed");
    }

    private boolean waitForStatusPrefix(String prefix, long ms) throws Exception {
        long deadline = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < deadline) {
            AtomicReference<String> text = new AtomicReference<>();
            onEdt(() -> text.set(win.getStatusLabel().getText()));
            if (text.get().startsWith(prefix)) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    @Test
    void shouldNotRepublishServiceAfterFullRebuildFailure() throws Exception {
        win = new HarnessWindow(null);
        FakeService old = new FakeService();
        FakeService pending = new FakeService();
        CountDownLatch gate = new CountDownLatch(1);
        onEdt(() -> win.setSearchService(old));
        win.gate = gate;
        win.firstService = pending;
        win.reopenSearchServiceAsync(java.nio.file.Paths.get("idx"), new IndexSettings());
        Thread.sleep(300);

        onEdt(() -> win.handleFullRebuildFailure());
        gate.countDown();

        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline && !pending.closed.get()) {
            Thread.sleep(50);
        }
        assertTrue(pending.closed.get(), "late candidate must be closed, not published");
        assertNull(win.getCurrentSearchService(), "service must stay unavailable after failed rebuild");
    }

    @Test
    void shouldReportIndexUnavailableOnFailedOpen() throws Exception {
        win = new HarnessWindow(null);
        win.openFailure = new RuntimeException("no commit");
        win.reopenSearchServiceAsync(java.nio.file.Paths.get("idx"), new IndexSettings());
        assertTrue(waitForStatus(expectedMsg(SearchException.Code.INDEX_UNAVAILABLE), 10_000));
    }
}
