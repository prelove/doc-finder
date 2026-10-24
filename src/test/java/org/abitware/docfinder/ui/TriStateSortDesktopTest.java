package org.abitware.docfinder.ui;

import org.abitware.docfinder.search.SearchResult;
import org.abitware.docfinder.search.SearchService;
import org.abitware.docfinder.search.TriState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.RowSorter;
import javax.swing.SortOrder;
import javax.swing.SwingUtilities;
import javax.swing.table.TableRowSorter;
import java.awt.GraphicsEnvironment;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Result-table sorting contract: a column click cycles asc → desc → default
 * (engine-returned) order; the "Restore default sort" button is only active
 * while a manual sort is applied; new results always restore engine order;
 * the selected row is preserved across reorders.
 */
class TriStateSortDesktopTest {

    private static final class W extends MainWindow {
        W(SearchService svc) { super(svc); }
    }

    private W win;

    @BeforeEach
    void setUp() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display");
        win = new W(r -> Collections.emptyList());
    }

    @AfterEach
    void tearDown() throws Exception {
        if (win != null) {
            SwingUtilities.invokeAndWait(() -> win.dispose());
        }
    }

    private static void onEdt(Runnable r) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) r.run();
        else SwingUtilities.invokeAndWait(r);
    }

    private Object field(String name) throws Exception {
        Field f = MainWindow.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(win);
    }

    @SuppressWarnings("unchecked")
    private TableRowSorter<javax.swing.table.TableModel> sorter() throws Exception {
        return (TableRowSorter<javax.swing.table.TableModel>) field("resultSorter");
    }

    private JButton resetButton() throws Exception {
        return (JButton) field("resetSortBtn");
    }

    private void populate(List<SearchResult> results) throws Exception {
        Method m = MainWindow.class.getDeclaredMethod("populateResults",
                String.class, List.class, long.class, TriState.class);
        m.setAccessible(true);
        onEdt(() -> {
            try { m.invoke(win, "q", results, 0L, TriState.FALSE); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
    }

    private List<SearchResult> sampleResults() {
        return Arrays.asList(
                new SearchResult("zeta.txt", "/x/zeta.txt", 2.0f),
                new SearchResult("alpha.txt", "/x/alpha.txt", 4.0f),
                new SearchResult("beta.txt", "/x/beta.txt", 1.0f));
    }

    private String viewName(int row) {
        return (String) win.getResultTable().getValueAt(row, 0);
    }

    @Test
    void shouldCycleAscDescDefaultOnSameColumn() throws Exception {
        populate(sampleResults());
        TableRowSorter<?> sorter = sorter();
        onEdt(() -> {
            sorter.toggleSortOrder(0);
            assertEquals(SortOrder.ASCENDING, sorter.getSortKeys().get(0).getSortOrder());
            assertEquals("alpha.txt", viewName(0));
            sorter.toggleSortOrder(0);
            assertEquals(SortOrder.DESCENDING, sorter.getSortKeys().get(0).getSortOrder());
            assertEquals("zeta.txt", viewName(0));
            sorter.toggleSortOrder(0);
            assertTrue(sorter.getSortKeys().isEmpty(),
                    "third click must clear the sort (arrow gone)");
            assertEquals("zeta.txt", viewName(0),
                    "default order is the engine order, not score order");
            assertEquals("beta.txt", viewName(2));
        });
    }

    @Test
    void shouldStartAscOnDifferentColumn() throws Exception {
        populate(sampleResults());
        TableRowSorter<?> sorter = sorter();
        onEdt(() -> {
            sorter.toggleSortOrder(0);
            sorter.toggleSortOrder(0); // desc on name
            sorter.toggleSortOrder(2); // switch to score
            assertEquals(2, sorter.getSortKeys().get(0).getColumn());
            assertEquals(SortOrder.ASCENDING, sorter.getSortKeys().get(0).getSortOrder());
        });
    }

    @Test
    void shouldEnableResetButtonOnlyWhileSorted() throws Exception {
        populate(sampleResults());
        JButton btn = resetButton();
        TableRowSorter<?> sorter = sorter();
        assertFalse(btn.isEnabled(), "reset is disabled in default order");
        onEdt(() -> sorter.toggleSortOrder(0));
        assertTrue(btn.isEnabled(), "reset enabled after manual sort");
        onEdt(() -> btn.doClick());
        assertTrue(sorter.getSortKeys().isEmpty(), "reset restores default order");
        assertFalse(btn.isEnabled());
        onEdt(() -> assertEquals("zeta.txt", viewName(0)));
    }

    @Test
    void shouldRestoreEngineOrderOnNewResults() throws Exception {
        populate(sampleResults());
        TableRowSorter<?> sorter = sorter();
        onEdt(() -> sorter.toggleSortOrder(0));
        assertFalse(sorter.getSortKeys().isEmpty());
        populate(sampleResults()); // next search result set
        assertTrue(sorter.getSortKeys().isEmpty(),
                "a new search must clear the old sort arrows");
        onEdt(() -> assertEquals("zeta.txt", viewName(0)));
    }

    @Test
    void shouldResetSortStateOnEmptyQuery() throws Exception {
        populate(sampleResults());
        TableRowSorter<?> sorter = sorter();
        onEdt(() -> sorter.toggleSortOrder(0));
        assertTrue(resetButton().isEnabled());
        // Clearing the query goes through doSearch's empty branch.
        Method doSearch = MainWindow.class.getDeclaredMethod("doSearch");
        doSearch.setAccessible(true);
        Method setQueryText = MainWindow.class.getDeclaredMethod("setQueryText", String.class);
        setQueryText.setAccessible(true);
        onEdt(() -> {
            try {
                setQueryText.invoke(win, "");
                doSearch.invoke(win);
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        assertTrue(sorter.getSortKeys().isEmpty(),
                "clearing the query must drop the manual sort state");
        assertFalse(resetButton().isEnabled());
    }

    @Test
    void shouldResetSortStateOnEmptyResults() throws Exception {
        populate(sampleResults());
        TableRowSorter<?> sorter = sorter();
        onEdt(() -> sorter.toggleSortOrder(0));
        assertTrue(resetButton().isEnabled());
        populate(java.util.Collections.<SearchResult>emptyList());
        assertTrue(sorter.getSortKeys().isEmpty(),
                "an empty result set must drop the manual sort state");
        assertFalse(resetButton().isEnabled());
    }

    @Test
    void shouldPreserveSelectedRowAcrossReorders() throws Exception {
        populate(sampleResults());
        TableRowSorter<?> sorter = sorter();
        onEdt(() -> {
            win.getResultTable().setRowSelectionInterval(0, 0); // "zeta.txt"
            sorter.toggleSortOrder(0); // asc: zeta moves to the last row
            int sel = win.getResultTable().getSelectedRow();
            assertEquals("zeta.txt", viewName(sel),
                    "selection follows the same row across sorting");
            sorter.setSortKeys(null); // restore via reset
            assertEquals(0, win.getResultTable().convertRowIndexToModel(
                    win.getResultTable().getSelectedRow()),
                    "selected model row is preserved after restore");
        });
    }
}
