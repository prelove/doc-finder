package org.abitware.docfinder.ui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Startup window state: the app opens maximized by default, while keeping a
 * minimum and a restore size so un-maximizing lands on a usable geometry.
 */
class MainWindowStartupTest {

    private static final class W extends MainWindow {
        W() { super(r -> Collections.emptyList()); }
    }

    private W win;

    @BeforeEach
    void setUp() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display");
        win = new W();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (win != null) {
            SwingUtilities.invokeAndWait(() -> win.dispose());
        }
    }

    @Test
    void shouldStartMaximized() {
        assertNotEquals(0, win.getExtendedState() & JFrame.MAXIMIZED_BOTH,
                "main window must open maximized");
    }

    @Test
    void shouldKeepRestoreSize() {
        assertTrue(win.getWidth() >= 900 && win.getHeight() >= 560,
                "restore geometry must stay at or above the minimum usable size");
    }
}
