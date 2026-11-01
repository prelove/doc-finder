package org.abitware.docfinder.web;

import org.junit.jupiter.api.Test;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class WebServerLifecycleTest {
    @Test
    void shouldReleaseWorkersAcrossRealHttpRestarts() throws Exception {
        WebServer server = new WebServer(0, "127.0.0.1");
        WebServer.BoundedRequestExecutor previous = null;
        for (int round = 0; round < 3; round++) {
            WebServer.BoundedRequestExecutor current = null;
            try {
                server.start();
                current = server.lastExecutor;
                assertNotSame(previous, current);
                previous = current;
                HttpURLConnection request = (HttpURLConnection) new URL(server.getBaseUrl() + "/").openConnection();
                request.setConnectTimeout(3000); request.setReadTimeout(3000);
                try {
                    assertEquals(200, request.getResponseCode());
                    try (java.io.InputStream in = request.getInputStream()) {
                        byte[] bytes = new byte[4096];
                        while (in.read(bytes) >= 0) { }
                    }
                } finally { request.disconnect(); }
            } finally {
                server.stop();
                if (current != null) assertDrained(current);
            }
        }
    }

    @Test
    void shouldPublishClickableWildcardAndIpv6Addresses() throws Exception {
        assertEquals("http://127.0.0.1:7070", new WebServer(7070, "0.0.0.0").getBaseUrl());
        assertEquals("http://[::1]:7070", new WebServer(7070, "::").getBaseUrl());
        assertEquals("http://[::1]:7070", new WebServer(7070, "::1").getBaseUrl());
        assertNotNull(new java.net.URI(new WebServer(7070, "::1").getBaseUrl()).getHost());
    }

    @Test
    void shouldStopActiveAndWaitingRequestsWithoutLeakingIntoRestart() throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0)) { port = probe.getLocalPort(); }
        WebServer server = new WebServer(port, "127.0.0.1");
        CountDownLatch entered = new CountDownLatch(WebServer.MAX_CONCURRENT_REQUESTS);
        AtomicInteger handlerEntries = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        server.testHandler = exchange -> {
            handlerEntries.incrementAndGet();
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            try { exchange.sendResponseHeaders(200, -1); } finally { exchange.close(); }
        };
        server.start();
        WebServer.BoundedRequestExecutor first = server.lastExecutor;
        assertNotNull(first);
        List<Socket> sockets = new ArrayList<>();
        try {
            // 10 个真实连接堵住 /__test_block：8 个占用执行槽，另 2 个已准入并等待执行槽。
            String url = server.getBaseUrl() + "/__test_block";
            for (int i = 0; i < 10; i++) {
                Socket socket = new Socket("127.0.0.1", port);
                socket.getOutputStream().write(
                        ("GET " + new URL(url).getPath() + " HTTP/1.1\r\nHost: x\r\n\r\n")
                                .getBytes(StandardCharsets.UTF_8));
                socket.getOutputStream().flush();
                sockets.add(socket);
            }
            assertTrue(entered.await(10, TimeUnit.SECONDS), "held handler must be reached");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (first.admittedCount() != 10 && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertEquals(WebServer.MAX_CONCURRENT_REQUESTS, first.activeCount());
            assertEquals(10, first.admittedCount(), "all ten HTTP exchanges must be admitted");
            assertEquals(8, handlerEntries.get(), "the two admitted waiters must not enter the handler");

            server.stop();
            assertTrue(first.awaitTermination(10, TimeUnit.SECONDS),
                    "stop must terminate held and waiting workers");
            assertEquals(0, first.admittedCount());
            assertEquals(0, first.activeCount());
        } finally {
            release.countDown();
            for (Socket socket : sockets) socket.close();
            server.stop();
        }

        // 同一端口可立即重绑；新一轮使用全新 executor，许可计数从零开始。
        server.start();
        try {
            assertNotSame(first, server.lastExecutor);
            assertEquals(0, server.lastExecutor.admittedCount());
            assertEquals(0, server.lastExecutor.activeCount());
            HttpURLConnection request = (HttpURLConnection) new URL(server.getBaseUrl() + "/").openConnection();
            request.setConnectTimeout(3000); request.setReadTimeout(3000);
            try {
                assertEquals(200, request.getResponseCode());
            } finally { request.disconnect(); }
        } finally {
            server.stop();
            assertDrained(server.lastExecutor);
        }
    }

    @Test
    void shouldReleaseListenerAndWorkersAfterStartFailure() throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0)) { port = probe.getLocalPort(); }
        WebServer server = new WebServer(port, "127.0.0.1");
        server.startFailpoint = () -> { throw new IllegalStateException("injected start failure"); };
        assertThrows(IllegalStateException.class, server::start);
        WebServer.BoundedRequestExecutor failed = server.lastExecutor;
        assertNotNull(failed);
        assertTrue(failed.awaitTermination(10, TimeUnit.SECONDS),
                "failed start must shut the request workers down");
        assertFalse(server.isRunning());
        assertDrained(failed);

        // 监听器已释放：同一端口可以重新绑定。stop(0) 的 socket 关闭由
        // dispatcher 线程异步完成，允许一个短窗口。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            try (ServerSocket probe = new ServerSocket(port)) {
                assertTrue(probe.isBound());
                break;
            } catch (java.net.BindException stillBound) {
                if (System.nanoTime() >= deadline) throw stillBound;
                Thread.sleep(50);
            }
        }

        // 恢复正常 start。
        server.startFailpoint = null;
        server.start();
        try {
            assertTrue(server.isRunning());
            assertNotSame(failed, server.lastExecutor);
        } finally {
            server.stop();
            assertDrained(server.lastExecutor);
        }
    }

    private static void assertDrained(WebServer.BoundedRequestExecutor executor) throws InterruptedException {
        assertNotNull(executor);
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), "request executor must terminate");
        assertTrue(executor.isTerminated());
        assertEquals(0, executor.admittedCount());
        assertEquals(0, executor.activeCount());
    }
}
