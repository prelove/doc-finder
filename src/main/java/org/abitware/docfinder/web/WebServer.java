package org.abitware.docfinder.web;

import com.sun.net.httpserver.HttpServer;
import org.abitware.docfinder.search.SearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

/**
 * 嵌入式 HTTP 服务器（可选），通过 REST API 暴露搜索功能。
 * 默认绑定 localhost:7070，仅在配置启用时才启动。
 * <p>
 * 端点：
 *   GET /            – 内嵌 HTML 搜索界面
 *   GET /api/search  – JSON 搜索结果（参数: q, scope, mode, ext, limit）
 *   GET /api/preview – 文本预览（参数: path）
 * </p>
 */
public class WebServer {

    private static final Logger log = LoggerFactory.getLogger(WebServer.class);

    private final int port;
    private final String bindAddress;
    private volatile HttpServer server;
    private volatile SearchService searchService;

    public WebServer(int port, String bindAddress) {
        this.port = port;
        this.bindAddress = (bindAddress == null || bindAddress.isEmpty()) ? "127.0.0.1" : bindAddress;
    }

    /** 更新搜索服务引用（在 index 打开后被 App 调用） */
    public void setSearchService(SearchService svc) {
        this.searchService = svc;
    }

    /** 启动服务器；若已在运行则为 no-op */
    public void start() throws IOException {
        if (server != null) return;
        InetSocketAddress addr = new InetSocketAddress(bindAddress, port);
        HttpServer s = HttpServer.create(addr, 16);
        s.setExecutor(Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "docfinder-web");
            t.setDaemon(true);
            return t;
        }));
        s.createContext("/api/search", new SearchHandler(() -> searchService));
        s.createContext("/api/preview", new PreviewHandler());
        s.createContext("/", new StaticHandler());
        s.start();
        server = s;
        log.info("Web interface started at http://{}:{}/", bindAddress, port);
    }

    /** 停止服务器（1 s 延迟） */
    public void stop() {
        HttpServer s = server;
        if (s != null) {
            server = null;
            s.stop(1);
            log.info("Web interface stopped.");
        }
    }

    public boolean isRunning() {
        return server != null;
    }

    public int getPort() {
        return port;
    }
}
