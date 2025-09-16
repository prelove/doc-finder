package org.abitware.docfinder.util;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * 进程单实例管理：
 * - 首实例：在 127.0.0.1:port 监听；收到 "ACTIVATE\n" 时回调 onActivate。
 * - 次实例：若 bind 失败则认为已有实例，向该端口发送 "ACTIVATE\n" 后返回 false。
 *
 * 端口计算：基于 appId 哈希 + 基数，避免多用户/多应用冲突。
 * 纯 Java、跨平台；崩溃后端口自动释放。
 */
public final class SingleInstance implements AutoCloseable {
    private final int port;
    private final ServerSocket server;
    private final ExecutorService es = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "single-instance-listener");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean closed = false;

    private SingleInstance(int port, ServerSocket server) {
        this.port = port;
        this.server = server;
    }

    /** 尝试成为首实例；成功返回 SingleInstance（需保留引用以便 close），失败返回 null 并已发送激活消息。 */
    public static SingleInstance tryAcquire(String appId, Consumer<String> onActivate) {
        Objects.requireNonNull(appId, "appId");
        Objects.requireNonNull(onActivate, "onActivate");

        int base = 44121;
        int port = base + (Math.abs(appId.hashCode()) % 2000);

        // 尝试作为首实例监听
        try {
            ServerSocket ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
            SingleInstance inst = new SingleInstance(port, ss);
            inst.startAcceptLoop(onActivate);
            return inst;
        } catch (BindException alreadyInUse) {
            // 已有实例：发送 ACTIVATE
            try (Socket s = new Socket("127.0.0.1", port);
                 OutputStream os = s.getOutputStream()) {
                os.write("ACTIVATE\n".getBytes(StandardCharsets.UTF_8));
                os.flush();
            } catch (IOException ignore) { /* 旧实例可能已退出/尚未就绪 */ }
            return null;
        } catch (IOException io) {
            // 其他异常：当作失败处理（不阻止应用，但也不保证单实例）
            return null;
        }
    }

    private void startAcceptLoop(Consumer<String> onActivate) {
        es.execute(() -> {
            byte[] buf = new byte[64];
            while (!closed) {
                try (Socket c = server.accept();
                     InputStream is = c.getInputStream()) {
                    int n = is.read(buf);
                    String cmd = (n > 0) ? new String(buf, 0, n, StandardCharsets.UTF_8).trim() : "";
                    if ("ACTIVATE".equalsIgnoreCase(cmd)) {
                        onActivate.accept(cmd);
                    }
                } catch (IOException e) {
                    if (!closed) {
                        // 短暂异常忽略，循环继续；close() 会把 closed=true 并关闭 server
                    }
                }
            }
        });
    }

    @Override public void close() {
        closed = true;
        try { server.close(); } catch (IOException ignore) {}
        es.shutdownNow();
    }

    /** 供次实例主动激活（当你想从脚本/其他进程触发时可用） */
    public static void sendActivate(String appId) {
        int base = 44121;
        int port = base + (Math.abs(appId.hashCode()) % 2000);
        try (Socket s = new Socket("127.0.0.1", port);
             OutputStream os = s.getOutputStream()) {
            os.write("ACTIVATE\n".getBytes(StandardCharsets.UTF_8));
            os.flush();
        } catch (IOException ignore) {}
    }
}
