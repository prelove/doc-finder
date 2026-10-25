package org.abitware.docfinder.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Port-setting contract — the no-Swing pieces of the "Web Server Port…"
 * menu flow: range validation and the occupied-port probe.
 */
class WebPortSettingsTest {

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
