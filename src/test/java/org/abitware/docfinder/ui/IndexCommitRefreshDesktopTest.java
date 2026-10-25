package org.abitware.docfinder.ui;

import org.abitware.docfinder.search.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.*;
import javax.swing.table.TableRowSorter;
import java.awt.GraphicsEnvironment;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/** Real EDT/timer/worker contracts; only the search engine boundary is controlled. */
class IndexCommitRefreshDesktopTest {
    @TempDir Path temp;
    private MainWindow window;
    private Service service;

    private static final class Service implements SearchService {
        final AtomicInteger calls = new AtomicInteger();
        final List<String> queries = new CopyOnWriteArrayList<>();
        volatile SearchOutcome outcome;
        volatile CountDownLatch entered;
        volatile CountDownLatch release;
        volatile boolean interrupted;

        @Override public List<SearchResult> search(SearchRequest request) { return searchDetailed(request).results; }
        @Override public SearchOutcome searchDetailed(SearchRequest request) {
            queries.add(request.query);
            calls.incrementAndGet();
            CountDownLatch gate = release;
            if (gate != null) {
                entered.countDown();
                try { gate.await(8, TimeUnit.SECONDS); }
                catch (InterruptedException e) { interrupted = true; Thread.currentThread().interrupt(); }
            }
            return outcome;
        }
        @Override public void close() { }
    }

    @BeforeEach void createWindow() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display");
        service = new Service();
        Path a = Files.createDirectory(temp.resolve("alpha.txt"));
        Files.write(a.resolve("alpha-preview.txt"), Collections.singleton("alpha preview"));
        Path z = Files.createDirectory(temp.resolve("zeta.txt"));
        Files.write(z.resolve("zeta-preview.txt"), Collections.singleton("zeta preview"));
        service.outcome = new SearchOutcome(Arrays.asList(new SearchResult("zeta.txt", z.toString(), 2),
                new SearchResult("alpha.txt", a.toString(), 1)), TriState.FALSE);
        edt(() -> window = new MainWindow(service));
    }

    @AfterEach void closeWindow() throws Exception {
        if (service != null && service.release != null) service.release.countDown();
        if (window != null) edt(window::dispose);
    }

    @Test void shouldPreserveManualSortSelectionPreviewAndDraftOnCommitRefresh() throws Exception {
        search("alpha");
        JTable table = (JTable) field("resultTable");
        @SuppressWarnings("unchecked") TableRowSorter<?> sorter = (TableRowSorter<?>) field("resultSorter");
        edt(() -> {
            sorter.setSortKeys(Collections.singletonList(new RowSorter.SortKey(0, SortOrder.ASCENDING)));
            table.setRowSelectionInterval(1, 1); // zeta by path, not the old row number
        });
        await(() -> preview().contains("zeta-preview.txt"), 5000);
        String originalPreview = preview();
        JComboBox<?> history = (JComboBox<?>) field("queryBox");
        int historySize = history.getItemCount();
        invoke("setQueryText", new Class<?>[]{String.class}, "unfinished draft");
        edt(() -> window.getSearchField().setCaretPosition(4));
        service.outcome = new SearchOutcome(Arrays.asList(service.outcome.results.get(1), service.outcome.results.get(0)), TriState.FALSE);
        commit();
        await(() -> service.calls.get() == 2 && fieldUnchecked("activeSearchWorker") == null, 5000);
        edt(() -> {
            assertFalse(sorter.getSortKeys().isEmpty(), "commit refresh retains manual sort");
            assertEquals(SortOrder.ASCENDING, sorter.getSortKeys().get(0).getSortOrder(), "commit refresh retains manual sort");
            assertEquals("zeta.txt", table.getValueAt(table.getSelectedRow(), 0), "selection follows path across engine reorder");
            assertEquals("unfinished draft", window.getSearchField().getText());
            assertEquals(4, window.getSearchField().getCaretPosition());
            assertEquals(historySize, history.getItemCount());
            assertEquals(originalPreview, preview());
        });
        assertEquals(Arrays.asList("alpha", "alpha"), service.queries, "refresh uses submitted query, not editor draft");
        search("beta");
        assertTrue(sorter.getSortKeys().isEmpty(), "new user query restores engine order");
    }

    @Test void shouldRefreshDuringContinuousCommitTraffic() throws Exception {
        search("alpha");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline && service.calls.get() < 2) {
            commit();
            Thread.sleep(70);
        }
        assertTrue(service.calls.get() >= 2, "continuous commits must not postpone the refresh indefinitely");
    }

    @Test void shouldMergeCommitsWhileARefreshIsInFlightWithoutCancellingIt() throws Exception {
        search("alpha");
        service.entered = new CountDownLatch(1);
        service.release = new CountDownLatch(1);
        commit();
        assertTrue(service.entered.await(5, TimeUnit.SECONDS));
        for (int i = 0; i < 14; i++) { commit(); Thread.sleep(80); }
        Thread.sleep(900);
        assertFalse(service.interrupted, "an in-flight refresh must not be cancelled by another commit");
        assertEquals(1, service.release.getCount(), "refresh must still be waiting for the search engine");
        assertEquals(2, service.calls.get(), "a commit burst must not enqueue/cancel repeated refresh searches");
        service.release.countDown();
        service.release = null;
        await(() -> service.calls.get() == 3 && fieldUnchecked("activeSearchWorker") == null, 5000);
        Thread.sleep(1000);
        assertEquals(3, service.calls.get(), "the in-flight burst is merged into one follow-up");
    }

    @Test void shouldNotSubmitEmptyQueriesOrRestartTimerAfterDisposal() throws Exception {
        commit();
        Thread.sleep(900);
        assertEquals(0, service.calls.get());
        search("alpha");
        edt(() -> { window.dispose(); invokeUnchecked("onIndexCommit"); });
        edt(() -> { }); // drain the callback queued by onIndexCommit
        assertFalse(((javax.swing.Timer) field("resultsRefreshTimer")).isRunning(), "late commit callback must not re-arm a disposed window");
        Thread.sleep(900);
        assertEquals(1, service.calls.get());
    }

    @Test void shouldExplainAnUnverifiedEmptyPageInsteadOfClaimingNoResults() throws Exception {
        service.outcome = new SearchOutcome(Collections.emptyList(), TriState.UNKNOWN, true);
        search("offline");
        assertTrue(status().toLowerCase(Locale.ROOT).contains("unverified"), status());
        assertFalse(status().contains("No results"), status());
        assertFalse(preview().contains("No results"), preview());
    }

    private void search(String query) throws Exception {
        int expected = service.calls.get() + 1;
        invoke("setQueryText", new Class<?>[]{String.class}, query);
        edt(() -> window.getSearchField().postActionEvent());
        await(() -> service.calls.get() >= expected && fieldUnchecked("activeSearchWorker") == null, 5000);
    }
    private void commit() throws Exception { invoke("onIndexCommit", new Class<?>[0]); }
    private String status() { return ((JLabel) fieldUnchecked("statusLabel")).getText(); }
    private String preview() { return String.valueOf(fieldUnchecked("lastPreviewInner")); }
    private Object field(String name) throws Exception {
        Field f = MainWindow.class.getDeclaredField(name); f.setAccessible(true); return f.get(window);
    }
    private Object fieldUnchecked(String name) {
        try { return field(name); } catch (Exception e) { throw new AssertionError(e); }
    }
    private void invokeUnchecked(String name) {
        try { invoke(name, new Class<?>[0]); } catch (Exception e) { throw new AssertionError(e); }
    }
    private void invoke(String name, Class<?>[] types, Object... args) throws Exception {
        Method m = MainWindow.class.getDeclaredMethod(name, types); m.setAccessible(true);
        edt(() -> { try { m.invoke(window, args); } catch (Exception e) { throw new AssertionError(e); } });
    }
    private static void edt(Runnable action) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) action.run(); else SwingUtilities.invokeAndWait(action);
    }
    private static void await(BooleanSupplier condition, long timeoutMs) throws Exception {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < end) {
            final boolean[] ready = {false};
            edt(() -> ready[0] = condition.getAsBoolean());
            if (ready[0]) return;
            Thread.sleep(30);
        }
        fail("condition did not become true within " + timeoutMs + " ms");
    }
}

