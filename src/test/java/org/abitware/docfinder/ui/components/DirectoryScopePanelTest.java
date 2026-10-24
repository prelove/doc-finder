package org.abitware.docfinder.ui.components;

import org.abitware.docfinder.util.Utils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Headless component tests for DirectoryScopePanel commit semantics (D5):
 * the committed value changes — and the callback fires — only on Enter,
 * confirmed source selection or Clear; typing, programmatic setters and
 * source-list refreshes never commit.
 */
@Execution(ExecutionMode.SAME_THREAD)
class DirectoryScopePanelTest {

    private static DirectoryScopePanel newPanel(AtomicInteger calls) {
        return new DirectoryScopePanel(calls::incrementAndGet);
    }

    private static String selected(DirectoryScopePanel p) {
        Object sel = p.getSourceCombo().getSelectedItem();
        return sel == null ? "" : sel.toString();
    }

    /** A valid absolute path on the current host (QA24-08 portability). */
    private static String hostAbs(String name) {
        return java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"))
                .toAbsolutePath().resolve(name).toString();
    }

    @Test
    void shouldShowAllSourcesForEmptyPath() {
        DirectoryScopePanel p = newPanel(new AtomicInteger());
        assertEquals("", p.getDirectoryPath());
        assertEquals("All sources", selected(p));
    }

    @Test
    void shouldShowSourceForEquivalentPath() {
        AtomicInteger calls = new AtomicInteger();
        DirectoryScopePanel p = newPanel(calls);
        String src = "E:\\Docs\\ProjectA";
        p.setSourceDirectories(Collections.singletonList(src));
        assumeTrue(Utils.isWindows());
        // Windows-equivalent input (case + separators) still shows the source
        p.setDirectoryPath("e:/docs/projecta");
        assertEquals(src, selected(p));
        assertEquals(0, calls.get(), "programmatic set must not commit");
    }

    @Test
    void shouldShowCustomForUnknownOrInvalidInput() {
        AtomicInteger calls = new AtomicInteger();
        DirectoryScopePanel p = newPanel(calls);
        p.setSourceDirectories(Collections.singletonList(hostAbs("Docs")));
        p.setDirectoryPath(hostAbs("SomewhereElse"));
        assertEquals("Custom", selected(p));
        p.setDirectoryPath("not a path");
        assertEquals("Custom", selected(p));
        assertEquals(0, calls.get());
    }

    @Test
    void shouldCommitOnceOnSourceSelection() {
        AtomicInteger calls = new AtomicInteger();
        DirectoryScopePanel p = newPanel(calls);
        String src = hostAbs("Docs");
        p.setSourceDirectories(Collections.singletonList(src));
        p.getSourceCombo().setSelectedItem(src);
        assertEquals(1, calls.get());
        assertEquals(src, p.getDirectoryPath());
        assertEquals(src, selected(p));
    }

    @Test
    void shouldCommitOnceOnClearAndAllSources() {
        AtomicInteger calls = new AtomicInteger();
        DirectoryScopePanel p = newPanel(calls);
        p.setDirectoryPath(hostAbs("Docs"));
        p.clearDirectory();
        assertEquals(1, calls.get());
        assertEquals("", p.getDirectoryPath());
        assertEquals("All sources", selected(p));

        // selecting All sources again = Clear semantics, one callback
        p.setDirectoryPath(hostAbs("Other"));
        p.getSourceCombo().setSelectedItem("All sources");
        assertEquals(2, calls.get());
        assertEquals("", p.getDirectoryPath());
    }

    @Test
    void shouldNotCommitOnPlainTyping() {
        AtomicInteger calls = new AtomicInteger();
        DirectoryScopePanel p = newPanel(calls);
        p.getPathField().setText(hostAbs("Typed/But/Not/Entered"));
        assertEquals(0, calls.get(), "typing without Enter must not commit");
        assertEquals("", p.getDirectoryPath(),
                "committed value unchanged until Enter");
    }

    @Test
    void shouldSyncComboDisplayWhileTypingWithoutCommitting() {
        AtomicInteger calls = new AtomicInteger();
        DirectoryScopePanel p = newPanel(calls);
        String docs = hostAbs("Docs");
        p.setSourceDirectories(Collections.singletonList(docs));
        p.getPathField().setText(hostAbs("Somewhere"));
        assertEquals("Custom", selected(p));
        p.getPathField().setText(docs);
        assertEquals(docs, selected(p));
        p.getPathField().setText("");
        assertEquals("All sources", selected(p));
        assertEquals(0, calls.get(), "typing must not commit");
        assertEquals("", p.getDirectoryPath());
    }

    @Test
    void shouldCommitOnceOnEnter() {
        AtomicInteger calls = new AtomicInteger();
        DirectoryScopePanel p = newPanel(calls);
        p.getPathField().setText(hostAbs("Docs"));
        p.getPathField().postActionEvent();
        assertEquals(1, calls.get());
        assertEquals(hostAbs("Docs"), p.getDirectoryPath());
    }

    @Test
    void shouldKeepRemovedSourcePathAsCustomAfterRefresh() {
        AtomicInteger calls = new AtomicInteger();
        DirectoryScopePanel p = newPanel(calls);
        String removed = hostAbs("Removed");
        p.setSourceDirectories(Arrays.asList(removed, hostAbs("Docs")));
        p.setDirectoryPath(removed);
        assertEquals(removed, selected(p));

        p.setSourceDirectories(Collections.singletonList(hostAbs("Docs")));
        assertEquals(0, calls.get(), "refresh must not commit or re-search");
        assertEquals(removed, p.getDirectoryPath(), "committed path survives refresh");
        assertEquals(removed, p.getPathField().getText());
        assertEquals("Custom", selected(p));
    }

    @Test
    void shouldKeepAppliedScopeVisibleWhileEditingDraft() {
        // QA24-02: after committing A, typing draft B must not hide A — the
        // actually applied scope stays visibly indicated as "Applied: <A>".
        AtomicInteger calls = new AtomicInteger();
        DirectoryScopePanel p = newPanel(calls);
        String a = java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"), "qa-applied-a").toString();
        String b = java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"), "qa-draft-b").toString();
        p.setSourceDirectories(Arrays.asList(a, b));
        p.getSourceCombo().setSelectedItem(a);
        assertEquals(1, calls.get());
        assertEquals(a, p.getDirectoryPath());

        p.getPathField().setText(b); // draft only, never commits
        assertEquals(1, calls.get(), "draft edit must not commit");
        assertEquals(a, p.getDirectoryPath(), "applied scope unchanged while editing");
        assertTrue(collectLabelText(p).contains(a),
                "applied scope must remain visibly indicated; labels: " + collectLabelText(p));
    }

    @Test
    void shouldShowAllSourcesAsAppliedScopeWhenUnrestricted() {
        DirectoryScopePanel p = newPanel(new AtomicInteger());
        assertTrue(collectLabelText(p).toLowerCase(java.util.Locale.ROOT)
                        .contains("all sources"),
                "unrestricted state names the applied scope; labels: " + collectLabelText(p));
    }

    private static String collectLabelText(java.awt.Component c) {
        StringBuilder out = new StringBuilder();
        collectLabelText(c, out);
        return out.toString();
    }

    private static void collectLabelText(java.awt.Component c, StringBuilder out) {
        if (c instanceof javax.swing.JLabel && c.isVisible()) {
            out.append(((javax.swing.JLabel) c).getText()).append('\n');
        }
        if (c instanceof java.awt.Container) {
            for (java.awt.Component child : ((java.awt.Container) c).getComponents()) {
                collectLabelText(child, out);
            }
        }
    }

    @Test
    void shouldToggleSourceWarning() {
        DirectoryScopePanel p = newPanel(new AtomicInteger());
        assertFalse(p.getWarningLabel().isVisible());
        p.setSourceWarning(true);
        assertTrue(p.getWarningLabel().isVisible());
        p.setSourceWarning(false);
        assertFalse(p.getWarningLabel().isVisible());
    }

    @Test
    void shouldDisableControls() {
        DirectoryScopePanel p = newPanel(new AtomicInteger());
        p.setDirectoryPath(hostAbs("Docs"));
        assertTrue(p.getPathField().isEnabled());
        p.setScopeControlsEnabled(false);
        assertFalse(p.getSourceCombo().isEnabled());
        assertFalse(p.getPathField().isEnabled());
        p.setScopeControlsEnabled(true);
        assertTrue(p.getSourceCombo().isEnabled());
        assertTrue(p.getPathField().isEnabled());
    }

    @Test
    void shouldGreyOutPathControlsForAllSourcesAndEnableForCustom() {
        AtomicInteger calls = new AtomicInteger();
        DirectoryScopePanel p = newPanel(calls);
        assertTrue(p.getSourceCombo().isEnabled());
        assertFalse(p.getPathField().isEnabled(), "All sources: path field greyed out");

        p.getSourceCombo().setSelectedItem("Custom");
        assertTrue(p.getPathField().isEnabled(), "Custom enables typing");
        assertEquals(0, calls.get(), "choosing Custom never commits");

        p.getPathField().setText(hostAbs("Typed"));
        p.getPathField().setText("");
        assertTrue(p.getPathField().isEnabled(), "emptying the text while typing must not lock the field");

        p.clearDirectory();
        assertFalse(p.getPathField().isEnabled(), "Clear returns to the greyed All sources state");
        p.setScopeControlsEnabled(false);
        p.setScopeControlsEnabled(true);
        assertFalse(p.getPathField().isEnabled(), "re-enabling controls keeps All sources greyed");
    }
}
