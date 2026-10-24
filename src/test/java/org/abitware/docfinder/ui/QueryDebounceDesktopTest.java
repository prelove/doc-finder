package org.abitware.docfinder.ui;

import org.abitware.docfinder.search.SearchRequest;
import org.abitware.docfinder.search.SearchResult;
import org.abitware.docfinder.search.SearchService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.JComboBox;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * D6 desktop input-event contract on a real MainWindow. An editable
 * JComboBox delivers one Enter commit on new text as TWO ActionEvents
 * (comboBoxChanged then comboBoxEdited); the listener must filter by event
 * semantics so every physical Enter — including two rapid Enters closer than
 * any dedup window — submits exactly once. Typing debounces into one delayed
 * submit; explicit submit cancels a pending debounce; programmatic writes
 * submit nothing and preserve the draft.
 */
class QueryDebounceDesktopTest {

    private static final class CapturingService implements SearchService {
        volatile int calls = 0;
        volatile SearchRequest lastRequest;

        @Override
        public List<SearchResult> search(SearchRequest request) {
            calls++;
            lastRequest = request;
            return Collections.emptyList();
        }

        @Override public void close() { }
    }

    /**
     * Counts executor submissions — the "each action submits once" contract.
     * (A fast second submit may legitimately cancel a pending worker before
     * its service call runs, so service-call counting cannot measure it.)
     */
    private static final class RecordingExecutor extends java.util.concurrent.AbstractExecutorService {
        volatile int submissions = 0;

        @Override public void execute(Runnable r) { submissions++; }
        @Override public void shutdown() { }
        @Override public List<Runnable> shutdownNow() { return Collections.emptyList(); }
        @Override public boolean isShutdown() { return true; }
        @Override public boolean isTerminated() { return true; }
        @Override public boolean awaitTermination(long t, java.util.concurrent.TimeUnit u) { return true; }
    }

    private static final class W extends MainWindow {
        W(SearchService svc) { super(svc); }
    }

    private CapturingService svc;
    private W win;
    private RecordingExecutor rec;

    @BeforeEach
    void setUp() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display");
        svc = new CapturingService();
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

    private void invokePrivate(String name, Class<?> sig, Object arg) throws Exception {
        Method m = MainWindow.class.getDeclaredMethod(name, sig);
        m.setAccessible(true);
        onEdt(() -> {
            try { m.invoke(win, arg); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
    }

    private JComboBox<?> queryBox() throws Exception {
        return (JComboBox<?>) field(win, "queryBox");
    }

    /** Real Enter path: the editor postActionEvent goes through JComboBox. */
    private void pressEnter() throws Exception {
        onEdt(() -> win.getSearchField().postActionEvent());
    }

    /** Swap the real search executor for a submission counter. */
    private void installRecorder() throws Exception {
        rec = new RecordingExecutor();
        Field f = MainWindow.class.getDeclaredField("searchExecutor");
        f.setAccessible(true);
        onEdt(() -> {
            try {
                ((java.util.concurrent.ExecutorService) f.get(win)).shutdownNow();
                f.set(win, rec);
            } catch (Exception e) { throw new RuntimeException(e); }
        });
    }

    private void awaitCalls(int expected, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline && svc.calls < expected) {
            Thread.sleep(40);
        }
        Thread.sleep(250); // let done()/EDT settle
    }

    @Test
    void shouldSubmitOnceForFirstEnterOnNewValue() throws Exception {
        win = new W(svc);
        installRecorder();
        // Programmatic text: no debounce armed, so any extra submit is a dup.
        invokePrivate("setQueryText", String.class, "alpha");
        pressEnter(); // comboBoxChanged + comboBoxEdited for a non-model value
        assertEquals(1, rec.submissions,
                "first Enter on a value new to the model must submit exactly once");
        Thread.sleep(400);
        assertEquals(1, rec.submissions, "no stray trailing submission");
    }

    @Test
    void shouldSubmitOnceForEachRapidEnter() throws Exception {
        win = new W(svc);
        installRecorder();
        invokePrivate("setQueryText", String.class, "beta");
        pressEnter();
        pressEnter(); // second independent Enter, far under any 100ms window
        assertEquals(2, rec.submissions,
                "two distinct Enter presses must each submit once — no time-window dedup");
        Thread.sleep(400);
        assertEquals(2, rec.submissions);
    }

    @Test
    void shouldSubmitOnceForDropdownPick() throws Exception {
        win = new W(svc);
        JComboBox<?> box = queryBox();
        @SuppressWarnings("unchecked")
        JComboBox<String> typed = (JComboBox<String>) box;
        onEdt(() -> {
            win.setVisible(true);
            typed.addItem("hist-pick");
            typed.setPopupVisible(true);
            typed.setSelectedItem("hist-pick");
            typed.setPopupVisible(false);
        });
        awaitCalls(1, 5_000);
        assertEquals(1, svc.calls, "a dropdown/history pick submits once");
        assertEquals("hist-pick", svc.lastRequest.query);
    }

    @Test
    void shouldDebounceTypingIntoSingleSubmit() throws Exception {
        win = new W(svc);
        onEdt(() -> win.getSearchField().setText("alpha"));
        awaitCalls(1, 5_000);
        assertEquals(1, svc.calls, "typing must coalesce into one delayed submit");

        onEdt(() -> win.getSearchField().setText("beta"));
        awaitCalls(2, 5_000);
        assertEquals(2, svc.calls);
        assertEquals("beta", svc.lastRequest.query);
    }

    @Test
    void shouldCancelPendingDebounceOnExplicitSubmit() throws Exception {
        win = new W(svc);
        onEdt(() -> win.getSearchField().setText("quick")); // arms the 250ms timer
        pressEnter(); // Enter before the timer fires
        awaitCalls(1, 5_000);
        assertEquals(1, svc.calls);
        Thread.sleep(500); // well past 250ms — the armed timer must not refire
        assertEquals(1, svc.calls, "explicit submit cancels the pending debounce");
    }

    @Test
    void shouldNotSubmitOnProgrammaticWriteAndPreserveDraft() throws Exception {
        win = new W(svc);
        invokePrivate("setQueryText", String.class, "draft-x");
        Thread.sleep(500);
        assertEquals(0, svc.calls, "programmatic write must submit nothing");

        // History-model refresh must not clobber the live draft.
        Method add = MainWindow.class.getDeclaredMethod("addToHistory", String.class);
        add.setAccessible(true);
        onEdt(() -> {
            try { add.invoke(win, "older"); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
        assertEquals("draft-x", win.getSearchField().getText(),
                "history rebuild must preserve the current draft");
        Thread.sleep(400);
        assertEquals(0, svc.calls, "history refresh must submit nothing");
    }
}
