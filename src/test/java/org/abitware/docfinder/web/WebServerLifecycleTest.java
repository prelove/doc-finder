package org.abitware.docfinder.web;

import org.junit.jupiter.api.Test;
import java.net.HttpURLConnection;
import java.net.URL;
import static org.junit.jupiter.api.Assertions.*;

class WebServerLifecycleTest {
    @Test
    void shouldReleaseWorkersAcrossRealHttpRestarts() throws Exception {
        long before = workers();
        WebServer server = new WebServer(0, "127.0.0.1");
        for (int round = 0; round < 3; round++) {
            try {
                server.start();
                HttpURLConnection request = (HttpURLConnection) new URL(server.getBaseUrl() + "/").openConnection();
                request.setConnectTimeout(3000); request.setReadTimeout(3000);
                try {
                    assertEquals(200, request.getResponseCode());
                    try (java.io.InputStream in = request.getInputStream()) {
                        byte[] bytes = new byte[4096];
                        while (in.read(bytes) >= 0) { }
                    }
                } finally { request.disconnect(); }
            } finally { server.stop(); }
        }
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        while (workers() > before && System.nanoTime() < deadline) Thread.sleep(20);
        assertTrue(workers() <= before, "HTTP workers must be released when their listener stops");
    }

    @Test
    void shouldPublishClickableWildcardAndIpv6Addresses() throws Exception {
        assertEquals("http://127.0.0.1:7070", new WebServer(7070, "0.0.0.0").getBaseUrl());
        assertEquals("http://[::1]:7070", new WebServer(7070, "::").getBaseUrl());
        assertEquals("http://[::1]:7070", new WebServer(7070, "::1").getBaseUrl());
        assertNotNull(new java.net.URI(new WebServer(7070, "::1").getBaseUrl()).getHost());
    }

    private static long workers() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.isAlive() && "docfinder-web".equals(thread.getName())).count();
    }
}
