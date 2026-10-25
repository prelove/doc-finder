package org.abitware.docfinder.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Port-setting contract — the no-Swing pieces of the "Web Server Port…"
 * menu flow: range validation and the occupied-port probe.
 */
class WebPortSettingsTest {

    @Test
    void shouldUseNewPortWhenStoppedServerIsEnabledAgain() throws Exception {
        org.abitware.docfinder.web.WebServer stopped = new org.abitware.docfinder.web.WebServer(freePort(), "127.0.0.1");
        stopped.start();
        stopped.stop();
        int next = freePort();
        java.util.concurrent.atomic.AtomicInteger saved = new java.util.concurrent.atomic.AtomicInteger();
        org.abitware.docfinder.web.WebServer changed = MainWindow.reconfigureWebServer(
                stopped, next, "127.0.0.1", null, saved::set);
        try {
            assertFalse(changed.isRunning(), "changing an offline server must not enable it");
            assertEquals(next, saved.get());
            changed.start();
            assertEquals("http://127.0.0.1:" + next, changed.getBaseUrl());
            assertEquals(200, httpStatus(changed.getBaseUrl()));
        } finally { changed.stop(); }
    }

    @Test
    void shouldKeepRunningServerAndConfigWhenNewPortIsOccupied() throws Exception {
        org.abitware.docfinder.web.WebServer old = new org.abitware.docfinder.web.WebServer(freePort(), "127.0.0.1");
        old.start();
        java.util.concurrent.atomic.AtomicInteger saved = new java.util.concurrent.atomic.AtomicInteger(old.getPort());
        try (java.net.ServerSocket occupied = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            assertThrows(java.io.IOException.class, () -> MainWindow.reconfigureWebServer(
                    old, occupied.getLocalPort(), "127.0.0.1", null, saved::set));
            assertEquals(old.getPort(), saved.get());
            assertEquals(200, httpStatus(old.getBaseUrl()));
        } finally { old.stop(); }
    }

    @Test
    void shouldRollBackReplacementWhenPortCannotBeSaved() throws Exception {
        org.abitware.docfinder.web.WebServer old = new org.abitware.docfinder.web.WebServer(freePort(), "127.0.0.1");
        old.start();
        int next = freePort();
        try {
            assertThrows(IllegalStateException.class, () -> MainWindow.reconfigureWebServer(
                    old, next, "127.0.0.1", null, port -> { throw new IllegalStateException("disk full"); }));
            assertEquals(200, httpStatus(old.getBaseUrl()));
            assertTrue(MainWindow.isWebPortAvailable("127.0.0.1", next));
        } finally { old.stop(); }
    }

    @Test
    void shouldSwapRunningServerAndTreatUnchangedPortAsNoOp() throws Exception {
        org.abitware.docfinder.web.WebServer old = new org.abitware.docfinder.web.WebServer(freePort(), "127.0.0.1");
        old.start();
        java.util.concurrent.atomic.AtomicInteger saved = new java.util.concurrent.atomic.AtomicInteger(old.getPort());
        org.abitware.docfinder.web.WebServer changed = old;
        try {
            assertSame(old, MainWindow.reconfigureWebServer(old, old.getPort(), "127.0.0.1", null,
                    port -> fail("unchanged port must not be saved or rebound")));
            int next = freePort();
            changed = MainWindow.reconfigureWebServer(old, next, "127.0.0.1", null, saved::set);
            assertFalse(old.isRunning());
            assertEquals(next, saved.get());
            assertEquals(200, httpStatus(changed.getBaseUrl()));
        } finally { old.stop(); changed.stop(); }
    }

    @Test
    void shouldReportConfigWriteFailureAndLeaveExistingFileUntouched(@org.junit.jupiter.api.io.TempDir java.nio.file.Path temp) throws Exception {
        String previousDirectory = System.getProperty("user.dir");
        java.nio.file.Path config = java.nio.file.Files.createDirectories(temp.resolve(".docfinder/config.properties"));
        try {
            System.setProperty("user.dir", temp.toString());
            org.abitware.docfinder.index.ConfigManager manager = new org.abitware.docfinder.index.ConfigManager();
            assertThrows(java.io.UncheckedIOException.class, () -> manager.setWebPort(7071));
            assertTrue(java.nio.file.Files.isDirectory(config), "failed save must leave the previous configuration untouched");
            try (java.util.stream.Stream<java.nio.file.Path> entries = java.nio.file.Files.list(config.getParent())) {
                assertEquals(1, entries.count(), "failed save cleans up its temporary file");
            }
        } finally {
            System.setProperty("user.dir", previousDirectory);
        }
    }

    @Test
    void shouldDescribePollFailuresWithoutClaimingSuccess() {
        org.abitware.docfinder.watch.NetPollerService.PollStats stats = new org.abitware.docfinder.watch.NetPollerService.PollStats();
        stats.accepted = false;
        assertTrue(MainWindow.manualPollStatus(stats).contains("not started"));
        stats.accepted = true; stats.cancelled = true;
        assertTrue(MainWindow.manualPollStatus(stats).contains("cancelled"));
        stats.cancelled = false; stats.failedRoots = 2;
        assertTrue(MainWindow.manualPollStatus(stats).contains("2 source(s) failed"));
        stats.successful = true;
        assertTrue(MainWindow.manualPollStatus(stats).startsWith("Polled:"));
    }

    private static int freePort() throws Exception {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        }
    }

    private static int httpStatus(String url) throws Exception {
        java.net.HttpURLConnection connection = (java.net.HttpURLConnection) new java.net.URL(url + "/").openConnection();
        connection.setConnectTimeout(3000); connection.setReadTimeout(3000);
        try {
            int status = connection.getResponseCode();
            try (java.io.InputStream in = connection.getInputStream()) {
                byte[] buffer = new byte[4096];
                while (in.read(buffer) >= 0) { }
            }
            return status;
        } finally { connection.disconnect(); }
    }

    @Test
    void shouldAcceptOnlyUsablePortRange() {
        assertFalse(MainWindow.isValidWebPort(0));
        assertFalse(MainWindow.isValidWebPort(80));
        assertFalse(MainWindow.isValidWebPort(1023));
        assertTrue(MainWindow.isValidWebPort(1024));
        assertTrue(MainWindow.isValidWebPort(7070));
        assertTrue(MainWindow.isValidWebPort(65535));
        assertFalse(MainWindow.isValidWebPort(65536));
        assertFalse(MainWindow.isValidWebPort(-1));
    }

    @Test
    void shouldDetectOccupiedAndFreePorts() throws Exception {
        // A socket already bound must be reported as occupied on the same
        // bind address — this is what blocks a bad change before it can
        // disturb a running server.
        try (java.net.ServerSocket taken = new java.net.ServerSocket()) {
            taken.setReuseAddress(false);
            taken.bind(new java.net.InetSocketAddress("127.0.0.1", 0));
            int port = taken.getLocalPort();
            assertFalse(MainWindow.isWebPortAvailable("127.0.0.1", port),
                    "a bound port must be rejected");
        }
        // A port bound to an ephemeral pick and then released must probe free.
        int free;
        try (java.net.ServerSocket s = new java.net.ServerSocket()) {
            s.setReuseAddress(false);
            s.bind(new java.net.InetSocketAddress("127.0.0.1", 0));
            free = s.getLocalPort();
        }
        assertTrue(MainWindow.isWebPortAvailable("127.0.0.1", free),
                "a released port must probe as available");
    }
}
