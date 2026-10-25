package org.abitware.docfinder.web;

import com.sun.net.httpserver.HttpServer;
import org.abitware.docfinder.index.SourceManager;
import org.abitware.docfinder.search.SearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

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
 *   GET  /api/file                – serve raw file for preview
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
    private ExecutorService executor;
    private volatile SearchService searchService;
    private final ShareManager shareManager = new ShareManager();

    public WebServer(int port, String bindAddress) {
        this.port = port;
        this.bindAddress = (bindAddress == null || bindAddress.isEmpty()) ? "127.0.0.1" : bindAddress;
    }

    /** Updates the search-service reference (called by App after the index is ready). */
    public void setSearchService(SearchService svc) {
        this.searchService = svc;
    }

    /** Starts the server; no-op if already running. */
    public synchronized void start() throws IOException {
        if (server != null) return;
        InetSocketAddress addr = new InetSocketAddress(bindAddress, port);
        HttpServer s = HttpServer.create(addr, 32);
        ExecutorService workers = new ThreadPoolExecutor(2, 8, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<Runnable>(128), r -> {
            Thread t = new Thread(r, "docfinder-web");
            t.setDaemon(true);
            return t;
        }, new ThreadPoolExecutor.AbortPolicy());
        s.setExecutor(workers);

        try {
            String base = getBaseUrl();
            ShareHandler shareHandler = new ShareHandler(shareManager, base);
            SourceManager sourceManager = new SourceManager();

            s.createContext("/api/search",  new SearchHandler(() -> searchService));
            s.createContext("/api/sources", new SourcesHandler(sourceManager::loadSearchRoots));
            s.createContext("/api/preview", new PreviewHandler());
            s.createContext("/api/file",    new FileServeHandler());
            s.createContext("/api/text",    new TextServeHandler());
            s.createContext("/api/share",   shareHandler);
            s.createContext("/share/",      new StaticHandler()); // public share pages → share.html
            s.createContext("/web/",        new StaticHandler()); // bundled JS / CSS
            s.createContext("/",            new StaticHandler()); // root → index.html
            s.start();
            executor = workers;
            server = s;
            log.info("Web interface started at http://{}:{}/", bindAddress, port);
        } catch (RuntimeException | Error failure) {
            s.stop(0);
            workers.shutdownNow();
            throw failure;
        }
    }

    /** Stops the listener and releases its owned request workers. */
    public synchronized void stop() {
        HttpServer s = server;
        server = null;
        ExecutorService workers = executor;
        executor = null;
        try {
            if (s != null) {
                s.stop(0);
                log.info("Web interface stopped.");
            }
        } finally {
            if (workers != null) workers.shutdownNow();
        }
    }

    public boolean isRunning() { return server != null; }
    public int getPort()       { return port; }
    public String getBindAddress() { return bindAddress; }

    /** Returns the base URL, e.g. {@code http://127.0.0.1:7070}. */
    public String getBaseUrl() {
        String host = bindAddress;
        if ("0.0.0.0".equals(host)) host = "127.0.0.1";
        else if ("::".equals(host) || "[::]".equals(host)
                || "0:0:0:0:0:0:0:0".equals(host)) host = "::1";
        if (host.indexOf(':') >= 0 && !host.startsWith("[")) host = "[" + host + "]";
        HttpServer active = server;
        int actualPort = active == null ? port : active.getAddress().getPort();
        return "http://" + host + ":" + actualPort;
    }
}
