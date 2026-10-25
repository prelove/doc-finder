package org.abitware.docfinder.util;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Hard deadlines for source I/O: only disposable helper JVMs touch source files. */
public final class IsolatedFileAccess {
    static final int MAGIC = 0x44464931;
    static final int MAX_PATH_BYTES = 32768;
    static final int MAX_TEXT_CHARS = 1000000;
    static final int MAX_TEXT_BYTES = MAX_TEXT_CHARS * 4;
    static final byte CLASSIFY = 1;
    static final byte ATTRIBUTES = 2;
    static final byte EXTRACT = 3;
    private static final AtomicInteger THREAD_IDS = new AtomicInteger();
    // Wire helpers are also used inside children; they must not initialize parent process pools.
    private static final class Shared {
        static final ScheduledThreadPoolExecutor REAPER = new ScheduledThreadPoolExecutor(1,
                daemonFactory("file-access-reaper-"));
        static final Pool METADATA = new Pool(FileAccessWorker.class.getName(), false, 30000);
        static final Pool PARSING = new Pool(FileAccessWorker.class.getName(), true, 30000);
        static {
            REAPER.setRemoveOnCancelPolicy(true);
            REAPER.setKeepAliveTime(30, TimeUnit.SECONDS);
            REAPER.allowCoreThreadTimeOut(true);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                METADATA.close();
                PARSING.close();
                REAPER.shutdownNow();
            }, "file-access-shutdown"));
        }
    }

    private IsolatedFileAccess() { }

    public enum Classification { ALIVE, GHOST, UNCERTAIN, ROOT_UNREACHABLE }

    public static Classification classify(String path, String normalizedRootScope, int timeoutMs) {
        try {
            String normalized = PathScope.normalizeDirectory(path);
            String root = PathScope.normalizeDirectory(normalizedRootScope);
            if (normalized == null || root == null
                    || (!normalized.equals(root) && !normalized.startsWith(PathScope.descendantPrefix(root)))) {
                return Classification.UNCERTAIN;
            }
            Path source = Paths.get(Utils.isWindows() ? Utils.canonicalizeWinPathString(path) : path);
            return Shared.METADATA.call(CLASSIFY, absolute(source), root, 0, timeoutMs).classification;
        } catch (IOException | IllegalArgumentException e) {
            return Classification.UNCERTAIN;
        }
    }

    public static Attributes attributes(Path path, int timeoutMs) throws IOException {
        return Shared.METADATA.call(ATTRIBUTES, absolute(path), "", 0, timeoutMs).attributes;
    }

    public static Extraction extract(Path path, int maxChars, int timeoutMs) throws IOException {
        if (maxChars < 1 || maxChars > MAX_TEXT_CHARS) {
            throw new IOException("Extraction character limit must be between 1 and " + MAX_TEXT_CHARS);
        }
        return Shared.PARSING.call(EXTRACT, absolute(path), "", maxChars, timeoutMs).extraction;
    }

    private static String absolute(Path path) throws IOException {
        if (path == null) throw new IOException("Missing source path");
        try {
            PathScope.normalizeIndexedPath(path); // Validate namespaces without folding the actual source filename.
            String value = path.toAbsolutePath().normalize().toString();
            return Utils.isWindows() ? Utils.canonicalizeWinPathString(value) : value;
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid source path", e);
        }
    }

    public static final class Attributes {
        private final long size, modified, created, accessed;
        private final boolean directory, regular;
        Attributes(long size, long modified, long created, long accessed, boolean directory, boolean regular) {
            this.size = size; this.modified = modified; this.created = created; this.accessed = accessed;
            this.directory = directory; this.regular = regular;
        }
        public long getSize() { return size; }
        public long getLastModifiedTime() { return modified; }
        public long getCreationTime() { return created; }
        public long getLastAccessTime() { return accessed; }
        public boolean isDirectory() { return directory; }
        public boolean isRegularFile() { return regular; }
    }

    public static final class Extraction {
        private final String text, mimeType;
        private final boolean truncated, successful;
        Extraction(String text, String mimeType, boolean truncated, boolean successful) {
            this.text = text; this.mimeType = mimeType;
            this.truncated = truncated; this.successful = successful;
        }
        public String getText() { return text; }
        public String getMimeType() { return mimeType; }
        public boolean isTruncated() { return truncated; }
        public boolean isSuccessful() { return successful; }
    }

    static final class Response {
        Classification classification;
        Attributes attributes;
        Extraction extraction;
    }

    static void writeString(DataOutputStream out, String text, int limit) throws IOException {
        if (text == null || text.length() > limit) throw new IOException("Invalid wire string length");
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > limit) throw new IOException("Wire string exceeds limit");
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    static String readString(DataInputStream in, int limit) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > limit) throw new IOException("Invalid wire string length");
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static ThreadFactory daemonFactory(String prefix) {
        return action -> {
            Thread thread = new Thread(action, prefix + THREAD_IDS.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    /** Two exchange threads, two processes and sixteen waiting requests per JVM-wide pool. */
    static final class Pool implements AutoCloseable {
        private final ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<Runnable>(16), daemonFactory("file-access-"));
        private final ArrayBlockingQueue<Worker> available = new ArrayBlockingQueue<>(2);
        private final Worker[] workers = { new Worker(), new Worker() };
        private final String workerClass;
        private final boolean parsing;
        private final int idleMs;
        private final java.util.concurrent.ScheduledFuture<?> reapTask;
        private volatile boolean closed;

        Pool(String workerClass, boolean parsing, int idleMs) {
            this.workerClass = workerClass; this.parsing = parsing; this.idleMs = idleMs;
            executor.allowCoreThreadTimeOut(true);
            for (Worker worker : workers) available.add(worker);
            reapTask = Shared.REAPER.scheduleWithFixedDelay(this::reap, idleMs, idleMs, TimeUnit.MILLISECONDS);
        }

        Response call(byte operation, String path, String root, int maxChars, int timeoutMs) throws IOException {
            if (timeoutMs <= 0) throw new IOException("Source I/O deadline must be positive");
            if (path == null || root == null || path.length() > MAX_PATH_BYTES || root.length() > MAX_PATH_BYTES
                    || path.getBytes(StandardCharsets.UTF_8).length > MAX_PATH_BYTES
                    || root.getBytes(StandardCharsets.UTF_8).length > MAX_PATH_BYTES) {
                throw new IOException("Source path exceeds protocol limit");
            }
            Request request = new Request(operation, path, root, maxChars);
            Future<Response> future;
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            try {
                future = executor.submit(request);
            } catch (RejectedExecutionException e) {
                throw new IOException("Source I/O queue is full or closed", e);
            }
            try {
                return future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                request.cancel();
                future.cancel(true);
                executor.purge();
                Thread.currentThread().interrupt();
                throw new IOException("Source I/O interrupted", e);
            } catch (TimeoutException e) {
                request.cancel();
                future.cancel(true);
                executor.purge();
                throw new IOException("Source I/O deadline exceeded", e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof IOException) throw (IOException) cause;
                throw new IOException("Source I/O worker failed", cause);
            }
        }

        private final class Request implements Callable<Response> {
            final byte operation;
            final String path, root;
            final int maxChars;
            final AtomicBoolean cancelled = new AtomicBoolean();
            volatile Process process;
            Request(byte operation, String path, String root, int maxChars) {
                this.operation = operation; this.path = path; this.root = root; this.maxChars = maxChars;
            }
            void cancel() {
                cancelled.set(true);
                Process current = process;
                // Kill before closing pipes: close() can wait for the exchange thread's read lock.
                if (current != null) current.destroyForcibly();
            }
            public Response call() throws Exception {
                Worker worker = available.take();
                try {
                    synchronized (worker) { worker.busy = true; }
                    if (cancelled.get() || closed) throw new IOException("Source I/O cancelled");
                    if (worker.invalid || worker.process == null || !worker.process.isAlive()) worker.start();
                    process = worker.process;
                    if (cancelled.get() || closed) { cancel(); throw new IOException("Source I/O cancelled"); }
                    DataOutputStream out = worker.output;
                    out.writeInt(MAGIC);
                    out.writeByte(operation);
                    writeString(out, path, MAX_PATH_BYTES);
                    writeString(out, root, MAX_PATH_BYTES);
                    out.writeInt(maxChars);
                    out.flush();
                    DataInputStream in = worker.input;
                    if (in.readInt() != MAGIC) throw new IOException("Invalid worker response");
                    int status = in.readUnsignedByte();
                    if (status == 1) throw new IOException(readString(in, 4096));
                    if (status != 0 || in.readByte() != operation) throw new IOException("Invalid worker response kind");
                    Response result = new Response();
                    if (operation == CLASSIFY) {
                        int ordinal = in.readUnsignedByte();
                        if (ordinal >= Classification.values().length) throw new IOException("Invalid classification");
                        result.classification = Classification.values()[ordinal];
                    } else if (operation == ATTRIBUTES) {
                        result.attributes = new Attributes(in.readLong(), in.readLong(), in.readLong(), in.readLong(),
                                in.readBoolean(), in.readBoolean());
                    } else if (operation == EXTRACT) {
                        String text = readString(in, Math.min(MAX_TEXT_BYTES, maxChars * 4));
                        if (text.length() > maxChars) throw new IOException("Worker text exceeds requested limit");
                        result.extraction = new Extraction(text, readString(in, 4096), in.readBoolean(), in.readBoolean());
                    } else throw new IOException("Invalid source I/O operation");
                    return result;
                } catch (Exception e) {
                    worker.destroy();
                    throw e;
                } finally {
                    if (cancelled.get() || closed) worker.destroy();
                    process = null;
                    synchronized (worker) { worker.lastUsed = System.nanoTime(); worker.busy = false; }
                    available.offer(worker);
                }
            }
        }

        private final class Worker {
            volatile Process process;
            DataInputStream input;
            DataOutputStream output;
            boolean busy;
            boolean invalid;
            long lastUsed = System.nanoTime();
            void start() throws IOException {
                destroy();
                if (process != null) throw new IOException("Previous source worker has not exited");
                String executable = Paths.get(System.getProperty("java.home"), "bin",
                        Utils.isWindows() ? "java.exe" : "java").toString();
                ProcessBuilder builder = new ProcessBuilder(executable, "-Xms16m", parsing ? "-Xmx160m" : "-Xmx48m",
                        "-XX:MaxMetaspaceSize=96m", "-XX:ReservedCodeCacheSize=32m", "-XX:+UseSerialGC",
                        "-Djava.awt.headless=true", "-Dlogback.configurationFile=logback-file-worker.xml",
                        "-cp", System.getProperty("java.class.path"), workerClass);
                // Never drain stderr with another potentially permanent thread or write application logs.
                builder.redirectError(ProcessBuilder.Redirect.INHERIT);
                process = builder.start();
                invalid = false;
                input = new DataInputStream(process.getInputStream());
                output = new DataOutputStream(process.getOutputStream());
            }
            void destroy() {
                Process current = process;
                invalid = true;
                if (current == null) return;
                current.destroyForcibly();
                try {
                    if (!current.waitFor(200, TimeUnit.MILLISECONDS)) return;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    if (current.isAlive()) return;
                }
                process = null;
                // This is called by the exchange owner or for an idle worker, never a blocked read's canceller.
                try { current.getOutputStream().close(); } catch (IOException ignored) { }
                try { current.getInputStream().close(); } catch (IOException ignored) { }
                try { current.getErrorStream().close(); } catch (IOException ignored) { }
            }
        }

        private void reap() {
            for (Worker worker : workers) {
                synchronized (worker) {
                    if (!worker.busy && System.nanoTime() - worker.lastUsed >= TimeUnit.MILLISECONDS.toNanos(idleMs)) {
                        worker.destroy();
                    }
                }
            }
        }

        public void close() {
            closed = true;
            reapTask.cancel(false);
            executor.shutdownNow();
            for (Worker worker : workers) {
                Process current = worker.process;
                if (current != null) current.destroyForcibly();
                synchronized (worker) { if (!worker.busy) worker.destroy(); }
            }
        }
    }
}
