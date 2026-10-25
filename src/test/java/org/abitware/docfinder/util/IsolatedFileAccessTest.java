package org.abitware.docfinder.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class IsolatedFileAccessTest {
    @TempDir Path directory;

    @Test
    void shouldStartColdWorkerBeforeApplyingSourceStatDeadline() throws Exception {
        Path source = Files.write(directory.resolve("cold-worker.txt"), new byte[] { 1 });
        try (IsolatedFileAccess.Pool pool = new IsolatedFileAccess.Pool(FileAccessWorker.class.getName(), false, 30000)) {
            IsolatedFileAccess.Response response = assertTimeoutPreemptively(java.time.Duration.ofSeconds(8),
                    () -> pool.call(IsolatedFileAccess.CLASSIFY, source.toString(),
                            PathScope.normalizeIndexedPath(directory), 0, 250));
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
                input.readInt();
                if (path.equals("delay")) Thread.sleep(60000);
                if (path.equals("exit")) System.exit(17);
                output.writeInt(path.equals("corrupt") ? 1 : IsolatedFileAccess.MAGIC);
                output.writeByte(0);
                output.writeByte(operation);
                if (path.equals("oversized")) output.writeInt(Integer.MAX_VALUE);
                else {
                    String name = ManagementFactory.getRuntimeMXBean().getName();
                    output.writeLong(Long.parseLong(name.substring(0, name.indexOf('@'))));
                    output.writeLong(2); output.writeLong(3); output.writeLong(4);
                    output.writeBoolean(false); output.writeBoolean(true);
                }
                output.flush();
            }
        }
    }
}
