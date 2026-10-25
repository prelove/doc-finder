package org.abitware.docfinder.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Local Watch activation decision: the checkbox carries session intent,
 * and the single activation path must report truthful states — never a
 * checked-but-not-running service. No local sources → wait for sources;
 * index not built → wait for indexing; otherwise start.
 */
class LiveWatchIntentTest {

    @Test
    void shouldWaitForSourcesWhenNoLocalRoots() {
        assertEquals(MainWindow.LiveWatchPlan.WAIT_NO_SOURCES,
                MainWindow.liveWatchPlan(false, true));
        assertEquals(MainWindow.LiveWatchPlan.WAIT_NO_SOURCES,
                MainWindow.liveWatchPlan(false, false));
    }

    @Test
    void shouldWaitForIndexWhenNotBuilt() {
        assertEquals(MainWindow.LiveWatchPlan.WAIT_NO_INDEX,
                MainWindow.liveWatchPlan(true, false));
    }

    @Test
    void shouldStartWhenSourcesExistAndIndexReady() {
        assertEquals(MainWindow.LiveWatchPlan.START,
                MainWindow.liveWatchPlan(true, true));
    }
}
