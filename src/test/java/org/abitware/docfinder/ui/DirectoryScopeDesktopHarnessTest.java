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
