package org.abitware.docfinder.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

class IsolatedFileAccessTest {
    @TempDir Path directory;

    @Test
    void shouldStartColdWorkerBeforeApplyingSourceStatDeadline() throws Exception {
        Path source = Files.write(directory.resolve("cold-worker.txt"), new byte[] { 1 });
        try (IsolatedFileAccess.Pool pool = new IsolatedFileAccess.Pool(FileAccessWorker.class.getName(), false, 30000)) {
            IsolatedFileAccess.Response response = pool.call(IsolatedFileAccess.CLASSIFY, source.toString(),
                    PathScope.normalizeIndexedPath(directory), 0, 10000);
            assertEquals(IsolatedFileAccess.Classification.ALIVE, response.classification,
                    "cold JVM startup must not falsely hide a healthy local result");
        }
    }

    @Test
    void shouldProvideProcessIsolationBoundary() {
        assertDoesNotThrow(() -> Class.forName("org.abitware.docfinder.util.IsolatedFileAccess"));
    }

    @Test
    void shouldReuseOnlyTwoProcessesForRepeatedRequests() throws Exception {
        try (IsolatedFileAccess.Pool pool = controlledPool(30000)) {
            Set<Long> processes = new HashSet<>();
            for (int i = 0; i < 30; i++) {
                processes.add(pool.call(IsolatedFileAccess.ATTRIBUTES, "normal", "", 0, 10000).attributes.getSize());
            }
            assertEquals(2, processes.size());
            assertEquals(2, liveProcesses(pool).size());
        }
    }

    @Test
    void shouldKillTimedOutProcessesAndRecoverWithoutLeakingWorkers() throws Exception {
        try (IsolatedFileAccess.Pool pool = controlledPool(30000)) {
            pool.call(IsolatedFileAccess.ATTRIBUTES, "normal", "", 0, 10000);
            pool.call(IsolatedFileAccess.ATTRIBUTES, "normal", "", 0, 10000);
            List<Process> originals = liveProcesses(pool);
            ExecutorService callers = Executors.newFixedThreadPool(2);
            try {
                List<Callable<Boolean>> requests = new ArrayList<>();
                for (int i = 0; i < 2; i++) requests.add(() -> {
                    assertThrows(IOException.class, () -> pool.call(IsolatedFileAccess.ATTRIBUTES, "delay", "", 0, 200));
                    return true;
                });
                for (Future<Boolean> result : callers.invokeAll(requests)) assertTrue(result.get());
            } finally { callers.shutdownNow(); }
            for (Process process : originals) assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            pool.call(IsolatedFileAccess.ATTRIBUTES, "normal", "", 0, 10000);
            assertTrue(liveProcesses(pool).size() <= 2);
        }
    }

    @Test
    void shouldRecoverFromWorkerExitAndProtocolCorruption() throws Exception {
        try (IsolatedFileAccess.Pool pool = controlledPool(30000)) {
            assertThrows(IOException.class, () -> pool.call(IsolatedFileAccess.ATTRIBUTES, "exit", "", 0, 10000));
            assertThrows(IOException.class, () -> pool.call(IsolatedFileAccess.ATTRIBUTES, "corrupt", "", 0, 10000));
            assertThrows(IOException.class, () -> pool.call(IsolatedFileAccess.EXTRACT, "oversized", "", 10, 10000));
            assertNotNull(pool.call(IsolatedFileAccess.ATTRIBUTES, "normal", "", 0, 10000).attributes);
        }
    }

    @Test
    void shouldBoundTheQueueAndReapIdleProcesses() throws Exception {
        try (IsolatedFileAccess.Pool pool = controlledPool(500)) {
            pool.call(IsolatedFileAccess.ATTRIBUTES, "normal", "", 0, 10000);
            List<Process> processes = liveProcesses(pool);
            for (Process process : processes) assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            ExecutorService callers = Executors.newFixedThreadPool(24);
            try {
                List<Callable<String>> requests = new ArrayList<>();
                for (int i = 0; i < 24; i++) requests.add(() -> {
                    try { pool.call(IsolatedFileAccess.ATTRIBUTES, "delay", "", 0, 1000); return "success"; }
                    catch (IOException e) { return e.getMessage(); }
                });
                List<Future<String>> results = callers.invokeAll(requests);
                int rejected = 0;
                for (Future<String> result : results) if (result.get().contains("queue")) rejected++;
                assertTrue(rejected >= 6, "Only two active and sixteen queued requests may be accepted");
                assertTrue(liveProcesses(pool).size() <= 2);
            } finally { callers.shutdownNow(); }
        }
    }

    @Test
    void shouldClassifyOnlyLexicallyScopedPathsAndDistinguishMissingRoots() throws Exception {
        Path file = directory.resolve("日本語.txt");
        Files.write(file, "hello".getBytes(StandardCharsets.UTF_8));
        String root = PathScope.normalizeIndexedPath(directory);
        assertEquals(IsolatedFileAccess.Classification.ALIVE,
                IsolatedFileAccess.classify(file.toString(), root, 10000));
        assertEquals(IsolatedFileAccess.Classification.GHOST,
                IsolatedFileAccess.classify(directory.resolve("missing").toString(), root, 10000));
        assertEquals(IsolatedFileAccess.Classification.UNCERTAIN,
                IsolatedFileAccess.classify(directory.getParent().resolve("outside").toString(), root, 10000));
        Path missingRoot = directory.resolve("missing-root");
        assertEquals(IsolatedFileAccess.Classification.ROOT_UNREACHABLE,
                IsolatedFileAccess.classify(missingRoot.toString(), PathScope.normalizeIndexedPath(missingRoot), 10000));
        assertEquals(5, IsolatedFileAccess.attributes(file, 10000).getSize());
        assertTrue(IsolatedFileAccess.attributes(file, 10000).isRegularFile());
        assertThrows(IOException.class, () -> IsolatedFileAccess.attributes(directory.resolve("absent"), 10000));
    }

    @Test
    void shouldParseAndTruncateInsideAHelperStartedFromSurefireClasspath() throws Exception {
        Path file = directory.resolve("input.txt");
        Files.write(file, "alpha beta gamma delta".getBytes(StandardCharsets.UTF_8));
        IsolatedFileAccess.Extraction result = IsolatedFileAccess.extract(file, 5, 30000);
        assertTrue(result.isSuccessful());
        assertTrue(result.isTruncated());
        assertTrue(result.getText().length() <= 5);
        assertTrue(result.getMimeType().contains("text/plain"));
        assertThrows(IOException.class, () -> IsolatedFileAccess.extract(file, Integer.MAX_VALUE, 10000));
    }

    @Test
    void shouldProbeUnknownExtensionsWithoutReadingThemInTheParent() throws Exception {
        Path text = directory.resolve("source.unusual");
        Files.write(text, "ordinary ASCII source text".getBytes(StandardCharsets.UTF_8));
        Path binary = directory.resolve("binary.unusual");
        Files.write(binary, new byte[]{ 0, 1, 2, 3 });
        assertTrue(IsolatedFileAccess.isTextLike(text, 10000));
        assertFalse(IsolatedFileAccess.isTextLike(binary, 10000));
    }

    @Test
    void shouldKeepAWarmingHelperWhenTheFirstCallerTimesOut() throws Exception {
        try (IsolatedFileAccess.Pool pool = new IsolatedFileAccess.Pool(SlowStartingWorker.class.getName(), false, 30000)) {
            assertThrows(IOException.class, () -> pool.call(IsolatedFileAccess.ATTRIBUTES, "normal", "", 0, 200));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            List<Process> warming = liveProcesses(pool);
            while (warming.isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(10);
                warming = liveProcesses(pool);
            }
            assertFalse(warming.isEmpty());
            Process first = warming.get(0);
            pool.call(IsolatedFileAccess.ATTRIBUTES, "normal", "", 0, 10000);
            pool.call(IsolatedFileAccess.ATTRIBUTES, "normal", "", 0, 10000);
            assertTrue(first.isAlive(), "An abandoned pre-I/O request must not repeatedly kill cold helpers");
        }
    }

    public static class SlowStartingWorker {
        public static void main(String[] args) throws Exception {
            Thread.sleep(1000);
            ControlledWorker.main(args);
        }
    }

    @Test
    void shouldApplyConfiguredWorkerAndQueueLimits() throws Exception {
        IsolatedFileAccess.Pool parsing = new IsolatedFileAccess.Pool(
                ControlledWorker.class.getName(), true, 30000);
        IsolatedFileAccess.Pool metadata = new IsolatedFileAccess.Pool(
                ControlledWorker.class.getName(), false, 30000);
        try {
            // 解析池 4 worker/8 等待，元数据池仅命名保持 2/16。
            assertEquals(4, parsing.workerCount());
            assertEquals(4, parsing.executorCorePoolSize());
            assertEquals(4, parsing.executorMaximumPoolSize());
            assertEquals(8, parsing.queueCapacity());
            assertEquals(2, metadata.workerCount());
            assertEquals(2, metadata.executorCorePoolSize());
            assertEquals(2, metadata.executorMaximumPoolSize());
            assertEquals(16, metadata.queueCapacity());

            // 用标记文件确认 4 个解析 worker 全部占用后，再填满 8 个等待槽；
            // 第 13 个请求必须同步拒绝且 cause 为 RejectedExecutionException。
            ExecutorService callers = Executors.newFixedThreadPool(16);
            List<Future<String>> futures = new ArrayList<>();
            try {
                List<Path> markers = new ArrayList<>();
                for (int i = 0; i < 4; i++) {
                    Path marker = directory.resolve("busy-" + i + ".mark");
                    markers.add(marker);
                    futures.add(callers.submit(() -> {
                        try { pool_call(parsing, "mark:" + marker); return "ok"; }
                        catch (IOException e) { return e.getMessage(); }
                    }));
                }
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                for (Path marker : markers) {
                    while (!Files.exists(marker) && System.nanoTime() < deadline) Thread.sleep(20);
                    assertTrue(Files.exists(marker), "worker marker " + marker);
                }
                for (int i = 0; i < 8; i++) {
                    futures.add(callers.submit(() -> {
                        try { pool_call(parsing, "delay"); return "ok"; }
                        catch (IOException e) { return e.getMessage(); }
                    }));
                }
                // 8 个 delay 请求是异步投递的；必须等队列真正填满，否则第 13
                // 个调用会被接受而非拒绝，随后变成普通的超时失败。
                long fullDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                while (parsing.queueRemainingCapacity() > 0 && System.nanoTime() < fullDeadline) {
                    Thread.sleep(20);
                }
                assertEquals(0, parsing.queueRemainingCapacity(), "queue must be full before the 13th call");
                IOException rejected = assertThrows(IOException.class,
                        () -> parsing.call(IsolatedFileAccess.ATTRIBUTES, "normal", "", 0, 10000));
                assertTrue(rejected.getCause() instanceof java.util.concurrent.RejectedExecutionException,
                        "full queue must surface the original rejection as the cause");
            } finally {
                for (Future<String> future : futures) future.cancel(true);
                callers.shutdownNow();
            }
        } finally {
            parsing.close();
            metadata.close();
        }
        // destroyForcibly 后进程退出是异步的，允许一个短窗口。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!liveProcesses(parsing).isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(liveProcesses(parsing).isEmpty(), "close must leave no helper process alive");
    }

    @Test
    void shouldKeepDeadlineAndWorkerRecyclingUnderParallelLoad() throws Exception {
        try (IsolatedFileAccess.Pool pool = new IsolatedFileAccess.Pool(
                ControlledWorker.class.getName(), true, 30000)) {
            // 预热 4 个解析 worker。
            ExecutorService callers = Executors.newFixedThreadPool(4);
            try {
                {
                    List<Callable<Long>> warmups = new ArrayList<>();
                    for (int i = 0; i < 4; i++) {
                        warmups.add(() -> pool.call(IsolatedFileAccess.ATTRIBUTES, "normal", "", 0, 30000)
                                .attributes.getSize());
                    }
                    for (Future<Long> warmup : callers.invokeAll(warmups)) warmup.get();
                }

                List<Process> originals = liveProcesses(pool);
                assertEquals(4, originals.size());

                // 一个超时请求只杀自己的进程；并行 gate 请求不被误杀。
                Path gate = directory.resolve("release.gate");
                List<Future<String>> parallel = new ArrayList<>();
                parallel.add(callers.submit(() -> {
                    try { pool_call(pool, "delay", 800); return "unexpected"; }
                    catch (IOException e) { return "timed-out"; }
                }));
                for (int i = 0; i < 3; i++) {
                    parallel.add(callers.submit(() -> {
                        try { return String.valueOf(pool_call(pool, "gate:" + gate)); }
                        catch (IOException e) { return "failed:" + e.getMessage(); }
                    }));
                }
                assertEquals("timed-out", parallel.get(0).get(30, TimeUnit.SECONDS));
                // 被杀的只有超时请求对应的进程；gate 放行为其余三个 worker。
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                int dead;
                do {
                    dead = 0;
                    for (Process p : originals) if (!p.isAlive()) dead++;
                    if (dead > 0) break;
                    Thread.sleep(20);
                } while (System.nanoTime() < deadline);
                assertTrue(dead >= 1, "timed-out helper must be killed");
                Files.write(gate, new byte[] { 1 });
                Set<Long> survivors = new HashSet<>();
                for (int i = 1; i < 4; i++) {
                    String result = parallel.get(i).get(30, TimeUnit.SECONDS);
                    assertFalse(result.startsWith("failed:"), result);
                    survivors.add(Long.parseLong(result));
                }
                assertEquals(3, survivors.size(), "parallel requests keep their own workers");
                // 下一请求在新进程上恢复。
                long pid = pool.call(IsolatedFileAccess.ATTRIBUTES, "normal", "", 0, 30000).attributes.getSize();
                assertFalse(survivors.contains(pid));
                assertTrue(liveProcesses(pool).size() <= 4);
            } finally {
                callers.shutdownNow();
                assertTrue(callers.awaitTermination(10, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void shouldDecodeClassificationWithoutLeavingAttributeBytesInTheProtocol() throws Exception {
        try (IsolatedFileAccess.Pool pool = controlledPool(30000)) {
            for (int i = 0; i < 4; i++) {
                IsolatedFileAccess.Response response = pool.call(IsolatedFileAccess.CLASSIFY,
                        "classification:GHOST", "", 0, 10000);
                assertEquals(IsolatedFileAccess.Classification.GHOST, response.classification);
            }
            assertNotNull(pool.call(IsolatedFileAccess.ATTRIBUTES, "normal", "", 0, 10000).attributes);
        }
    }

    @Test
    void shouldSeparateClassifyStartupFromSourceIoDeadline() throws Exception {
        Path control = startupControl();
        Path gates = Files.createDirectory(directory.resolve("worker-gates"));
        Files.write(control, gates.toString().getBytes(StandardCharsets.UTF_8));
        ExecutorService callers = Executors.newSingleThreadExecutor();
        try (IsolatedFileAccess.Pool cold = new IsolatedFileAccess.Pool(
                GatedStartingWorker.class.getName(), true, 30000)) {
            Future<IsolatedFileAccess.Response> response = callers.submit(
                    () -> cold.call(IsolatedFileAccess.CLASSIFY, "normal", "", 0, 1000));
            awaitFile(gates.resolve("startup-entered"));
            // 已确认子进程还在握手之前：即使经过 source I/O 预算，也不能提前超时。
            assertThrows(TimeoutException.class, () -> response.get(1200, TimeUnit.MILLISECONDS));
            assertFalse(response.isDone(), "startup gate must keep the request pending");
            Files.write(gates.resolve("startup-release"), new byte[] { 1 });
            assertEquals(IsolatedFileAccess.Classification.ALIVE,
                    response.get(10, TimeUnit.SECONDS).classification);

            Path ioEntered = gates.resolve("io-entered");
            Future<IsolatedFileAccess.Response> blocked = callers.submit(
                    () -> cold.call(IsolatedFileAccess.CLASSIFY, "mark:" + ioEntered, "", 0, 2000));
            awaitFile(ioEntered);
            java.util.concurrent.ExecutionException timeout = assertThrows(
                    java.util.concurrent.ExecutionException.class, () -> blocked.get(10, TimeUnit.SECONDS));
            assertInstanceOf(IOException.class, timeout.getCause());
            assertInstanceOf(TimeoutException.class, timeout.getCause().getCause());
        } finally {
            Files.write(gates.resolve("startup-release"), new byte[] { 1 });
            callers.shutdownNow();
            assertTrue(callers.awaitTermination(10, TimeUnit.SECONDS));
            Files.deleteIfExists(control);
        }
    }

    private static Path startupControl() {
        // 子进程在受限 /proc 环境下未必能发现 parent PID；工作目录由 ProcessBuilder
        // 继承，用它限定测试控制文件，避免把启动阶段测试绑到进程枚举权限。
        return Paths.get(System.getProperty("java.io.tmpdir"),
                "docfinder-worker-startup-" + Integer.toHexString(System.getProperty("user.dir").hashCode()));
    }

    private static void awaitFile(Path path) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!Files.exists(path) && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(Files.exists(path), "worker did not reach gate " + path);
    }

    public static class GatedStartingWorker {
        public static void main(String[] args) throws Exception {
            Path gates = Paths.get(new String(Files.readAllBytes(startupControl()), StandardCharsets.UTF_8));
            Files.write(gates.resolve("startup-entered"), new byte[] { 1 });
            awaitFile(gates.resolve("startup-release"));
            ControlledWorker.main(args);
        }
    }

    private static long pool_call(IsolatedFileAccess.Pool pool, String path) throws IOException {
        return pool_call(pool, path, 30000);
    }

    private static long pool_call(IsolatedFileAccess.Pool pool, String path, int timeoutMs) throws IOException {
        return pool.call(IsolatedFileAccess.ATTRIBUTES, path, "", 0, timeoutMs).attributes.getSize();
    }

    private static IsolatedFileAccess.Pool controlledPool(int idleMs) {
        return new IsolatedFileAccess.Pool(ControlledWorker.class.getName(), false, idleMs);
    }

    private static List<Process> liveProcesses(IsolatedFileAccess.Pool pool) throws Exception {
        Field workers = IsolatedFileAccess.Pool.class.getDeclaredField("workers");
        workers.setAccessible(true);
        List<Process> result = new ArrayList<>();
        for (Object worker : (Object[]) workers.get(pool)) {
            Field processField = worker.getClass().getDeclaredField("process");
            processField.setAccessible(true);
            Process process = (Process) processField.get(worker);
            if (process != null && process.isAlive()) result.add(process);
        }
        return result;
    }

    /** Fault injection lives in the test classpath, never in the shipped helper. */
    public static class ControlledWorker {
        public static void main(String[] args) throws Exception {
            DataInputStream input = new DataInputStream(System.in);
            DataOutputStream output = new DataOutputStream(new FileOutputStream(FileDescriptor.out));
            output.writeInt(IsolatedFileAccess.MAGIC);
            output.flush();
            while (true) {
                try { input.readInt(); } catch (IOException e) { return; }
                byte operation = input.readByte();
                String path = IsolatedFileAccess.readString(input, IsolatedFileAccess.MAX_PATH_BYTES);
                IsolatedFileAccess.readString(input, IsolatedFileAccess.MAX_PATH_BYTES);
                int maxChars = input.readInt();
                if (path.equals("delay")) Thread.sleep(60000);
                if (path.startsWith("mark:")) {
                    // 标记文件握手：主进程据此确认 worker 已被占住，避免靠短
                    // sleep 猜测调度时序。
                    Files.write(Paths.get(path.substring(5)), new byte[] { 1 });
                    Thread.sleep(60000);
                }
                if (path.startsWith("gate:")) {
                    // 等到测试放行文件出现后再响应，用于并行占用 worker。
                    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
                    while (!Files.exists(Paths.get(path.substring(5))) && System.nanoTime() < until) {
                        Thread.sleep(50);
                    }
                }
                if (path.equals("exit")) System.exit(17);
                output.writeInt(path.equals("corrupt") ? 1 : IsolatedFileAccess.MAGIC);
                output.writeByte(0);
                output.writeByte(operation);
                if (path.equals("oversized")) output.writeInt(Integer.MAX_VALUE);
                else if (operation == IsolatedFileAccess.CLASSIFY) {
                    IsolatedFileAccess.Classification classification = path.startsWith("classification:")
                            ? IsolatedFileAccess.Classification.valueOf(path.substring("classification:".length()))
                            : IsolatedFileAccess.Classification.ALIVE;
                    output.writeByte(classification.ordinal());
                } else if (operation == IsolatedFileAccess.ATTRIBUTES) {
                    output.writeLong(ProcessHandle.current().pid());
                    output.writeLong(2); output.writeLong(3); output.writeLong(4);
                    output.writeBoolean(false); output.writeBoolean(true);
                } else if (operation == IsolatedFileAccess.EXTRACT) {
                    String text = "controlled";
                    boolean truncated = text.length() > maxChars;
                    IsolatedFileAccess.writeString(output, text.substring(0, Math.min(text.length(), maxChars)),
                            maxChars * 4);
                    IsolatedFileAccess.writeString(output, "text/plain", 4096);
                    output.writeBoolean(truncated);
                    output.writeBoolean(true);
                } else if (operation == IsolatedFileAccess.PROBE_TEXT) {
                    output.writeBoolean(true);
                } else {
                    throw new IOException("Unsupported test operation " + operation);
                }
                output.flush();
            }
        }
    }
}
