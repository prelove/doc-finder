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
    void shouldRunRealIndexAllLifecycleAndPublishService() throws Exception {
        // QA24-06: exercise the ACTUAL indexAllSources entry (not the
        // isIndexing flag alone): real sources.txt + config.properties in the
        // isolated .docfinder base, real LuceneIndexer, real done() reopen
        // path — publishing goes through the harness open hook.
        Path base = org.abitware.docfinder.util.AppPaths.getBaseDir();
        java.nio.file.Files.createDirectories(base);
        Path tmp = java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"));
        Path src = tmp.resolve("qa-indexall-src-" + System.nanoTime());
        java.nio.file.Files.createDirectories(src.resolve("child"));
        java.nio.file.Files.write(src.resolve("f1.txt"), "alpha one".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        java.nio.file.Files.write(src.resolve("child").resolve("f2.txt"), "alpha two".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Path idx = tmp.resolve("qa-indexall-idx-" + System.nanoTime());
        java.nio.file.Files.write(base.resolve("sources.txt"),
                (src.toAbsolutePath() + "|0").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        java.nio.file.Files.write(base.resolve("config.properties"),
                ("index.dir=" + idx.toAbsolutePath()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try {
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
        } finally {
            java.nio.file.Files.deleteIfExists(base.resolve("sources.txt"));
            java.nio.file.Files.deleteIfExists(base.resolve("config.properties"));
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
