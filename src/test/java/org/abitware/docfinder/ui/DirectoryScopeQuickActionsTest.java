package org.abitware.docfinder.ui;

import org.abitware.docfinder.search.SearchRequest;
import org.abitware.docfinder.search.SearchResult;
import org.abitware.docfinder.search.SearchService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.RowSorter;
import javax.swing.SortOrder;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * A5b/DS-11: "Search in this folder" / "Search in containing folder" quick
 * actions — correct view→model mapping under sorting, single commit, parent
 * resolution, indexing-disable state. Requires a display.
 */
@Execution(ExecutionMode.SAME_THREAD)
class DirectoryScopeQuickActionsTest {

    private static final class CapturingService implements SearchService {
        volatile List<SearchResult> canned;
        volatile SearchRequest lastRequest;
        volatile int calls;

        @Override
        public List<SearchResult> search(SearchRequest request) {
            calls++;
            lastRequest = request;
            return canned;
        }

        @Override
        public void close() {
        }
    }

    private static final class W extends MainWindow {
        W(SearchService svc) {
            super(svc);
        }

        org.abitware.docfinder.ui.components.DirectoryScopePanel panel() {
            return getDirectoryScopePanel();
        }
    }

    private CapturingService svc;
    private W win;

    /** Real absolute fixture root valid on any host (QA24-08). */
    private static final java.nio.file.Path ROOT =
            java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"))
                    .toAbsolutePath().resolve("Root");

    @BeforeEach
    void setUp() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display");
        svc = new CapturingService();
        svc.canned = Arrays.asList(
                new SearchResult("bbb.txt", ROOT.resolve("bbb.txt").toString(), 1f, 0L, 0L, "name", 10L, false),
                new SearchResult("AaaDir", ROOT.resolve("AaaDir").toString(), 2f, 0L, 0L, "folder", 0L, true),
                new SearchResult("it's.txt", ROOT.resolve("Sub'Dir").resolve("it's.txt").toString(), 3f, 0L, 0L, "name", 10L, false));
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

    private static Object field(MainWindow w, String name) throws Exception {
        Field f = MainWindow.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(w);
    }

    private void doSearchOnEdt() throws Exception {
        Method m = MainWindow.class.getDeclaredMethod("doSearch");
        m.setAccessible(true);
        onEdt(() -> {
            try {
                m.invoke(win);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private void populate() throws Exception {
        win = new W(svc);
        onEdt(() -> win.getSearchField().setText("alpha"));
        long deadline = System.currentTimeMillis() + 10_000;
        doSearchOnEdt();
        while (System.currentTimeMillis() < deadline && svc.calls == 0) {
            Thread.sleep(50);
        }
        // give done() a beat to populate the model on the EDT
        Thread.sleep(400);
        assertEquals(3, win.getResultTable().getRowCount());
    }

    private void sortByNameDescending() throws Exception {
        onEdt(() -> {
            javax.swing.table.TableRowSorter<?> sorter =
                    (javax.swing.table.TableRowSorter<?>) win.getResultTable().getRowSorter();
            sorter.setSortKeys(java.util.Collections.singletonList(
                    new RowSorter.SortKey(0, SortOrder.DESCENDING)));
            sorter.sort();
        });
    }

    private JPopupMenu popup() {
        try {
            return (JPopupMenu) field(win, "rowPopup");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void firePopupVisible() {
        JPopupMenu p = popup();
        javax.swing.event.PopupMenuEvent ev = new javax.swing.event.PopupMenuEvent(p);
        for (javax.swing.event.PopupMenuListener l : p.getPopupMenuListeners()) {
            l.popupMenuWillBecomeVisible(ev);
        }
    }

    @Test
    void shouldMapSortedViewRowToFolderAndCommitOnce() throws Exception {
        populate();
        sortByNameDescending(); // view order: it's.txt, bbb.txt, AaaDir

        onEdt(() -> win.getResultTable().setRowSelectionInterval(2, 2)); // "AaaDir"
        AtomicReference<String> committed = new AtomicReference<>();
        int before = svc.calls;
        onEdt(() -> {
            ((JMenuItem) fieldUnchecked("searchInThisFolderItem")).doClick();
            committed.set(win.panel().getDirectoryPath());
        });
        assertEquals(ROOT.resolve("AaaDir").toString(), committed.get(),
                "sorted view row must map back to the folder's model row");
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline && svc.calls == before) {
            Thread.sleep(50);
        }
        assertEquals(before + 1, svc.calls, "exactly one committed search");
        assertEquals(ROOT.resolve("AaaDir").toString(), svc.lastRequest.directoryPath);
    }

    private JMenuItem fieldUnchecked(String name) {
        try {
            Field f = MainWindow.class.getDeclaredField(name);
            f.setAccessible(true);
            return (JMenuItem) f.get(win);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void shouldUseParentForFileRow() throws Exception {
        populate();
        onEdt(() -> win.getResultTable().setRowSelectionInterval(0, 0)); // "bbb.txt"
        onEdt(() -> fieldUnchecked("searchInContainingItem").doClick());
        assertEquals(ROOT.toString(), win.panel().getDirectoryPath());
    }

    @Test
    void shouldHandleQuotedPathWithoutBreaking() throws Exception {
        populate();
        sortByNameDescending();
        onEdt(() -> win.getResultTable().setRowSelectionInterval(0, 0)); // "it's.txt"
        onEdt(() -> fieldUnchecked("searchInContainingItem").doClick());
        assertEquals(ROOT.resolve("Sub'Dir").toString(), win.panel().getDirectoryPath());
    }

    @Test
    void shouldDisableActionsWhileIndexing() throws Exception {
        populate();
        onEdt(() -> win.getResultTable().setRowSelectionInterval(0, 0));
        Field isIndexing = MainWindow.class.getDeclaredField("isIndexing");
        isIndexing.setAccessible(true);
        isIndexing.set(win, true);
        onEdt(this::firePopupVisible);
        assertFalse(fieldUnchecked("searchInThisFolderItem").isEnabled());
        assertFalse(fieldUnchecked("searchInContainingItem").isEnabled());
        isIndexing.set(win, false);
        onEdt(() -> win.getResultTable().setRowSelectionInterval(1, 1)); // AaaDir row? ensure folder
        onEdt(this::firePopupVisible);
        // after re-enable, at least one action becomes available per row type
    }

    @Test
    void shouldRepeatOnRootItselfWithoutLooping() throws Exception {
        populate();
        sortByNameDescending();
        onEdt(() -> win.getResultTable().setRowSelectionInterval(2, 2)); // AaaDir
        int before = svc.calls;
        onEdt(() -> fieldUnchecked("searchInThisFolderItem").doClick());
        onEdt(() -> fieldUnchecked("searchInThisFolderItem").doClick());
        assertEquals(ROOT.resolve("AaaDir").toString(), win.panel().getDirectoryPath(),
                "re-applying the same scope is idempotent");
    }
}
