package org.abitware.docfinder.web;

import com.sun.net.httpserver.HttpServer;
import org.abitware.docfinder.search.SearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

/**
 * Embedded HTTP server that exposes the DocFinder web interface.
 *
 * <p>Endpoints:
 * <pre>
 *   GET  /                        – main search UI (index.html)
 *   GET  /web/<asset>             – bundled static assets (JS, CSS)
 *   GET  /share/{token}           – public file-share page (share.html)
 *   GET  /api/search              – JSON search results
 *   GET  /api/preview             – text snippet preview (legacy)
 *   GET  /api/file                – serve raw file for jit-viewer preview
 *   GET  /api/kkfileview/*        – proxy to embedded kkFileView server
 *   POST /api/share/create        – create a share link
 *   GET  /api/share/list          – list all shares
 *   POST /api/share/revoke        – revoke a share
 *   GET  /api/share/{token}/info  – share metadata
 *   GET  /api/share/{token}/file  – download / stream shared file
 * </pre>
 */
public class WebServer {

    private static final Logger log = LoggerFactory.getLogger(WebServer.class);

    private final int port;
    private final String bindAddress;
    private volatile HttpServer server;
    private volatile SearchService searchService;
    private volatile KkFileViewServer kkFileViewServer;
    private final ShareManager shareManager = new ShareManager();

    public WebServer(int port, String bindAddress) {
        this.port = port;
        this.bindAddress = (bindAddress == null || bindAddress.isEmpty()) ? "127.0.0.1" : bindAddress;
    }

    /** Updates the search-service reference (called by App after the index is ready). */
    public void setSearchService(SearchService svc) {
        this.searchService = svc;
    }

    /** Updates the kkFileView server reference (called by App after kkFileView is started). */
    public void setKkFileViewServer(KkFileViewServer kk) {
        this.kkFileViewServer = kk;
    }

    /** Starts the server; no-op if already running. */
    public void start() throws IOException {
        if (server != null) return;
        InetSocketAddress addr = new InetSocketAddress(bindAddress, port);
        HttpServer s = HttpServer.create(addr, 32);
        s.setExecutor(Executors.newFixedThreadPool(8, r -> {
            Thread t = new Thread(r, "docfinder-web");
            t.setDaemon(true);
            return t;
        }));

        String base = "http://" + bindAddress + ":" + port;
        ShareHandler shareHandler = new ShareHandler(shareManager, base);

        s.createContext("/api/search",  new SearchHandler(() -> searchService));
        s.createContext("/api/preview", new PreviewHandler());
        s.createContext("/api/file",    new FileServeHandler());
        s.createContext("/api/kkfileview", new KkFileViewProxyHandler(() -> kkFileViewServer));
        s.createContext("/api/share",   shareHandler);
        s.createContext("/share/",      new StaticHandler()); // public share pages → share.html
        s.createContext("/web/",        new StaticHandler()); // bundled JS / CSS
        s.createContext("/",            new StaticHandler()); // root → index.html
        s.start();
        server = s;
        log.info("Web interface started at http://{}:{}/", bindAddress, port);
    }

    /** Stops the server (1 s drain). */
    public void stop() {
        HttpServer s = server;
        if (s != null) {
            server = null;
            s.stop(1);
            log.info("Web interface stopped.");
        }
    }

    public boolean isRunning() { return server != null; }
    public int getPort()       { return port; }
    public String getBindAddress() { return bindAddress; }

    /** Returns the base URL, e.g. {@code http://127.0.0.1:7070}. */
    public String getBaseUrl() {
        return "http://" + bindAddress + ":" + port;
    }
}
