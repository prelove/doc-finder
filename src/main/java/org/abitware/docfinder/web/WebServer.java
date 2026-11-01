package org.abitware.docfinder.web;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.abitware.docfinder.index.SourceManager;
import org.abitware.docfinder.search.SearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

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

    // 两级限流沿用旧池 (max=8, ABQ=128) 的资源上限：最多同时运行 8 个
    // exchange，另接收最多 128 个等待执行槽的任务，合计 136 为已接收任务的
    // 硬上限（已接收但尚未调度者瞬时可能多于 128，不再单独承诺瞬时队长）。
    static final int MAX_CONCURRENT_REQUESTS = 8;
    static final int MAX_PENDING_REQUESTS = 128;
    static final int MAX_ADMITTED_REQUESTS = MAX_CONCURRENT_REQUESTS + MAX_PENDING_REQUESTS;

    private final int port;
    private final String bindAddress;
    // start/stop 覆盖 bind、listener 启停与 executor 关闭等 I/O；虚拟线程在
    // monitor 内等待会固定载体线程，故用实例 ReentrantLock 保持互斥与幂等。
    private final ReentrantLock lifecycleLock = new ReentrantLock();
    private volatile HttpServer server;
    private volatile BoundedRequestExecutor executor;
    private volatile SearchService searchService;
    private final ShareManager shareManager = new ShareManager();
    // 测试接缝：记录最近一次 start 创建的 executor，供测试在其终止后核对
    // 许可归还与 termination，不能借此操作生产路径。
    volatile BoundedRequestExecutor lastExecutor;
    // 测试接缝：contexts 注册完、s.start() 之前回调，用于注入启动失败。
    volatile Runnable startFailpoint;
    // 测试接缝：非空时注册 /__test_block context，让测试能持有真实 HTTP 请求。
    volatile HttpHandler testHandler;

    public WebServer(int port, String bindAddress) {
        this.port = port;
        this.bindAddress = (bindAddress == null || bindAddress.isEmpty()) ? "127.0.0.1" : bindAddress;
    }

    /** Updates the search-service reference (called by App after the index is ready). */
    public void setSearchService(SearchService svc) {
        this.searchService = svc;
    }

    /** Starts the server; no-op if already running. */
    public void start() throws IOException {
        lifecycleLock.lock();
        try {
            if (server != null) return;
            InetSocketAddress addr = new InetSocketAddress(bindAddress, port);
            HttpServer s = HttpServer.create(addr, 32);
            // 每次 start 都用全新的 executor 与 semaphore：旧任务的 finally
            // 只归还旧实例的许可，不会污染新一轮容量。
            BoundedRequestExecutor workers = null;
            try {
                workers = new BoundedRequestExecutor();
                lastExecutor = workers;
                s.setExecutor(workers);

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
                HttpHandler hook = testHandler;
                if (hook != null) s.createContext("/__test_block", hook);
                Runnable failpoint = startFailpoint;
                if (failpoint != null) failpoint.run();
                s.start();
                executor = workers;
                server = s;
                log.info("Web interface started at http://{}:{}/", bindAddress, port);
            } catch (RuntimeException | Error failure) {
                // 启动任一步失败都要释放监听器与 executor，且字段不发布。
                // JDK 的 HttpServer.stop(0) 对从未 start() 的实例不会关闭
                // 监听 socket（端口保持绑定），故先尽力 start 再 stop(0)。
                try { s.start(); } catch (RuntimeException ignored) { }
                s.stop(0);
                if (workers != null) workers.shutdownNow();
                throw failure;
            }
        } finally {
            lifecycleLock.unlock();
        }
    }

    /** Stops the listener and releases its owned request workers. */
    public void stop() {
        lifecycleLock.lock();
        try {
            HttpServer s = server;
            server = null;
            BoundedRequestExecutor workers = executor;
            executor = null;
            try {
                if (s != null) {
                    // stop(0) 沿用原停机语义：立即断开连接，不是等待下载完成
                    // 的优雅停机；不在此处新增 awaitTermination。
                    s.stop(0);
                    log.info("Web interface stopped.");
                }
            } finally {
                // shutdownNow 中断正在运行的 exchange 与等待执行槽的虚拟
                // 线程；ExecutorService.close() 的无界等待禁止使用。
                if (workers != null) workers.shutdownNow();
            }
        } finally {
            lifecycleLock.unlock();
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

    /**
     * Bounded adapter between {@link HttpServer#setExecutor} and a
     * virtual-thread-per-task worker: at most {@link #MAX_ADMITTED_REQUESTS}
     * admitted exchanges and at most {@link #MAX_CONCURRENT_REQUESTS} actually
     * running at any time. All contexts share the two semaphores.
     */
    static final class BoundedRequestExecutor implements Executor {
        private final ExecutorService workers;
        private final Semaphore admitted;
        private final Semaphore active;
        private final int maxAdmitted;
        private final int maxActive;
        private final AtomicBoolean accepting = new AtomicBoolean(true);
        // 测试接缝：在 Admission 分配前注入失败，验证尚无任务对象时的 lease 归属。
        volatile Runnable admissionFailpoint;

        BoundedRequestExecutor() {
            this(Executors.newThreadPerTaskExecutor(
                    Thread.ofVirtual().name("docfinder-web-", 0).factory()),
                    MAX_CONCURRENT_REQUESTS, MAX_ADMITTED_REQUESTS);
        }

        /** 测试接缝：注入延迟/排队执行器与自定义上限。 */
        BoundedRequestExecutor(ExecutorService workers, int maxActive, int maxAdmitted) {
            this.workers = workers;
            this.maxActive = maxActive;
            this.maxAdmitted = maxAdmitted;
            this.admitted = new Semaphore(maxAdmitted, true);
            this.active = new Semaphore(maxActive, true);
        }

        @Override
        public void execute(Runnable exchange) {
            // 拒绝发生在创建虚拟线程之前，绝不在 HttpServer 调度线程上阻塞：
            // 超载沿用 AbortPolicy 语义同步抛 RejectedExecutionException，
            // 由 HttpServer 关闭连接，Executor 层拿不到 HttpExchange，不承诺 503。
            if (!accepting.get() || !admitted.tryAcquire()) {
                throw new RejectedExecutionException("Web request admission limit reached");
            }
            Admission task = null;
            try {
                Runnable failpoint = admissionFailpoint;
                if (failpoint != null) failpoint.run();
                task = new Admission(exchange);
                workers.execute(task);
            } catch (RuntimeException | Error failure) {
                // acquire 成功起由提交方持有 lease：构造失败时直接归还；构造后
                // 与任务 finally 共用一次性标志，连“执行后再抛异常”也不会多还。
                if (task == null) admitted.release();
                else task.releaseOnce();
                throw failure;
            }
        }

        void shutdownNow() {
            accepting.set(false);
            List<Runnable> pending = workers.shutdownNow();
            for (Runnable task : pending) {
                // shutdown 返还的未启动任务也要一次性归还其 lease。
                if (task instanceof Admission) ((Admission) task).releaseOnce();
            }
        }

        int activeCount() { return maxActive - active.availablePermits(); }
        int admittedCount() { return maxAdmitted - admitted.availablePermits(); }
        boolean isTerminated() { return workers.isTerminated(); }
        boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return workers.awaitTermination(timeout, unit);
        }

        private final class Admission implements Runnable {
            private final Runnable exchange;
            private final AtomicBoolean released = new AtomicBoolean();
            private boolean activeAcquired;

            Admission(Runnable exchange) {
                this.exchange = exchange;
            }

            @Override
            public void run() {
                try {
                    // 等待执行槽的虚拟线程可中断地 acquire；被中断则不再运行
                    // exchange，只在 finally 归还 admitted。
                    active.acquire();
                    activeAcquired = true;
                    // shutdownNow 先置 accepting=false 再逐个中断：被中断的运行中任务
                    // 会先归还执行槽，等待者可能在自己的中断到达前抢到槽；此处复查以
                    // 保证停机后等待中的任务绝不运行 exchange。
                    if (!accepting.get()) return;
                    exchange.run();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    if (activeAcquired) active.release();
                    releaseOnce();
                }
            }

            void releaseOnce() {
                if (released.compareAndSet(false, true)) admitted.release();
            }
        }
    }
}
