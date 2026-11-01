package org.abitware.docfinder.index;

import org.abitware.docfinder.index.LuceneIndexer.BatchObserver;
import org.abitware.docfinder.index.LuceneIndexer.BatchOptions;
import org.abitware.docfinder.index.LuceneIndexer.BatchSource;
import org.abitware.docfinder.index.LuceneIndexer.Scheduling;
import org.abitware.docfinder.util.IsolatedFileAccess;
import org.abitware.docfinder.util.Utils;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.index.Term;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class LuceneIndexerBackpressureTest {
    @TempDir Path temporary;
    private String originalDirectory;

    @BeforeEach
    void isolate() {
        originalDirectory = System.getProperty("user.dir");
        System.setProperty("user.dir", temporary.toString());
        LuceneIndexer.resetExtractionStateForTests();
    }

    @AfterEach
    void restore() {
        try {
            Tombstones.close();
            LuceneIndexer.resetExtractionStateForTests();
        } finally {
            System.setProperty("user.dir", originalDirectory);
        }
    }

    private IndexSettings settings() {
        IndexSettings s = new IndexSettings();
        s.parseTimeoutSec = 30;
        return s;
    }

    /** 快速注入提取器：不触碰真实 helper，直接返回固定正文。 */
    private BatchOptions fastOptions(BatchObserver observer) {
        BatchOptions o = new BatchOptions();
        o.observer = observer == null ? BatchObserver.NOOP : observer;
        o.extractor = (path, maxChars, timeoutMs) ->
                IsolatedFileAccess.Extraction.of("body-" + path.getFileName(), "text/plain", false, true);
        return o;
    }

    private Path fileTree(String name, int files, int subdirs) throws IOException {
        Path root = temporary.resolve(name);
        Files.createDirectories(root);
        for (int i = 0; i < files; i++) {
            Path dir = root.resolve("d" + (i % subdirs));
            Files.createDirectories(dir);
            Files.write(dir.resolve(String.format("f%03d.txt", i)), "x".getBytes());
        }
        return root;
    }

    private static IndexWriter writerOf(LuceneIndexer indexer) throws Exception {
        Field f = LuceneIndexer.class.getDeclaredField("writer");
        f.setAccessible(true);
        return (IndexWriter) f.get(indexer);
    }

    private static void awaitTrue(Check check, String what) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            if (check.holds()) return;
            Thread.sleep(5);
        }
        fail("timed out waiting for " + what);
    }

    private interface Check { boolean holds(); }

    @Test
    void shouldBoundSubmittedTasksBeforeCreatingVirtualThreads() throws Exception {
        Path s1 = fileTree("bp-src1", 60, 3);
        Path s2 = fileTree("bp-src2", 60, 3);

        AtomicInteger created = new AtomicInteger();
        AtomicInteger startedOrdinal = new AtomicInteger();
        AtomicInteger running = new AtomicInteger();
        AtomicInteger highWater = new AtomicInteger();
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch attempt9 = new CountDownLatch(1);
        Semaphore gate = new Semaphore(0);
        CountDownLatch finished8 = new CountDownLatch(LuceneIndexer.MAX_IN_FLIGHT_INDEX_TASKS);

        BatchObserver observer = new BatchObserver() {
            @Override public void onDispatchAttempt(Path path, boolean directory) {
                if (attempts.incrementAndGet() == 9) attempt9.countDown();
            }
            @Override public void onTaskCreated(Path path) { created.incrementAndGet(); }
            @Override public void onTaskStarted(Path path) {
                // 所有任务都在 gate 上等待：放行一个才恰好补进一个新任务。
                int now = running.incrementAndGet();
                highWater.accumulateAndGet(now, Math::max);
                startedOrdinal.incrementAndGet();
                try {
                    gate.acquire();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            @Override public void onTaskFinished(Path path) {
                running.decrementAndGet();
                finished8.countDown();
            }
        };

        BatchOptions o = fastOptions(observer);
        try (LuceneIndexer indexer = new LuceneIndexer(temporary.resolve("bp-idx"), settings(), o)) {
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread owner = new Thread(() -> {
                try {
                    indexer.indexFolders(Arrays.asList(s1, s2), false);
                } catch (Throwable t) {
                    failure.set(t);
                }
            }, "bp-owner");
            owner.start();

            try {
                // 前 8 个任务被堵在 gate；创建完成不代表任务已经开始执行。
                assertTrue(attempt9.await(15, TimeUnit.SECONDS), "9th dispatch was never attempted");
                awaitTrue(() -> created.get() >= 8, "8 task creations");
                awaitTrue(() -> startedOrdinal.get() >= 8, "8 task starts");
                assertEquals(8, created.get(), "9th task must not be created while permits are held");
                assertEquals(8, highWater.get());
                assertTrue(LuceneIndexer.INDEX_TASK_PERMITS.availablePermits() == 0,
                        "all 8 permits must be held by blocked tasks");

                // 放行一个：恰好多创建一个任务。
                gate.release(1);
                awaitTrue(() -> created.get() == 9, "exactly one more task after one release");
                assertEquals(9, created.get());
            } finally {
                // 断言失败也必须先放行并排空，再关闭 writer。
                gate.release(500);
                owner.join(30000);
            }
            assertFalse(owner.isAlive());
            assertNull(failure.get());
            assertEquals(8, LuceneIndexer.INDEX_TASK_PERMITS.availablePermits(),
                    "all permits must return after drain");
        }
    }

    @Test
    void shouldSharePermitsAcrossSourcesAndReleaseThemAfterFailures() throws Exception {
        Path s1 = fileTree("share-src1", 12, 2);
        Path s2 = fileTree("share-src2", 12, 2);
        List<BatchSource> sources = new ArrayList<>();
        BatchObserver tracker = new BatchObserver() {
            @Override public void onSourceSubmitted(BatchSource source) {
                sources.add(source);
            }
        };

        // a) 正常批 + builder 抛异常：许可归还、事务失败。
        Semaphore permits = new Semaphore(LuceneIndexer.MAX_IN_FLIGHT_INDEX_TASKS, true);
        Path badFile = findTxt(s1);
        BatchObserver failBuild = new BatchObserver() {
            @Override public void onSourceSubmitted(BatchSource source) { sources.add(source); }
            @Override public void beforeBuild(Path path) {
                if (path.equals(badFile)) throw new IllegalStateException("injected builder failure");
            }
        };
        BatchOptions o1 = fastOptions(failBuild);
        o1.permits = permits;
        try (LuceneIndexer indexer = new LuceneIndexer(temporary.resolve("share-idx1"), settings(), o1)) {
            assertThrows(IOException.class, () -> indexer.indexFolders(Arrays.asList(s1, s2), false));
        }
        assertEquals(8, permits.availablePermits(), "permits must return after builder failure drain");
        for (BatchSource s : sources) {
            // 排空后 phaser 只剩 source 自身初始 party（=1），latch 归零。
            assertEquals(1, s.registeredParties());
            assertEquals(0, s.pendingDone());
        }

        // b) 文件任务提交被拒绝：基础设施致命 → IOException + rollback。
        Semaphore permits2 = new Semaphore(8, true);
        ExecutorService real = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("bp-test-", 0).factory());
        AtomicInteger execCalls = new AtomicInteger();
        ExecutorService rejectingFiles = new AbstractExecutorService() {
            @Override public void shutdown() { real.shutdown(); }
            @Override public List<Runnable> shutdownNow() { return real.shutdownNow(); }
            @Override public boolean isShutdown() { return real.isShutdown(); }
            @Override public boolean isTerminated() { return real.isTerminated(); }
            @Override public boolean awaitTermination(long t, TimeUnit u) throws InterruptedException {
                return real.awaitTermination(t, u);
            }
            @Override public void execute(Runnable command) {
                // 第一次 execute 是 source 任务；之后的文件分发全部被拒。
                if (execCalls.incrementAndGet() > 1) {
                    throw new RejectedExecutionException("injected file-task rejection");
                }
                real.execute(command);
            }
        };
        BatchOptions o2 = fastOptions(null);
        o2.permits = permits2;
        o2.executorFactory = () -> rejectingFiles;
        try (LuceneIndexer indexer = new LuceneIndexer(temporary.resolve("share-idx2"), settings(), o2)) {
            assertThrows(IOException.class, () -> indexer.indexFolder(s1));
        }
        assertEquals(8, permits2.availablePermits(), "rejected task must not leak its permit");

        // c) source 提交被拒绝：初始 party 与 latch 归还，事务致命。
        ExecutorService rejectingAll = new AbstractExecutorService() {
            @Override public void shutdown() { }
            @Override public List<Runnable> shutdownNow() { return Collections.emptyList(); }
            @Override public boolean isShutdown() { return true; }
            @Override public boolean isTerminated() { return true; }
            @Override public boolean awaitTermination(long t, TimeUnit u) { return true; }
            @Override public void execute(Runnable command) {
                throw new RejectedExecutionException("injected source rejection");
            }
        };
        BatchOptions o3 = fastOptions(null);
        o3.executorFactory = () -> rejectingAll;
        try (LuceneIndexer indexer = new LuceneIndexer(temporary.resolve("share-idx3"), settings(), o3)) {
            assertThrows(IOException.class, () -> indexer.indexFolder(s1));
        }

        // d) 拥有者被中断：排空后 InterruptedIOException，许可全归还。
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch hold = new CountDownLatch(1);
        Semaphore permits4 = new Semaphore(8, true);
        BatchObserver blockOnce = new BatchObserver() {
            private final AtomicBoolean once = new AtomicBoolean();
            @Override public void onTaskStarted(Path path) {
                if (once.compareAndSet(false, true)) {
                    entered.countDown();
                    try {
                        hold.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        };
        BatchOptions o4 = fastOptions(blockOnce);
        o4.permits = permits4;
        try (LuceneIndexer indexer = new LuceneIndexer(temporary.resolve("share-idx4"), settings(), o4)) {
            AtomicReference<Throwable> outcome = new AtomicReference<>();
            AtomicBoolean interruptedFlag = new AtomicBoolean();
            Thread owner = new Thread(() -> {
                try {
                    indexer.indexFolder(s1);
                } catch (Throwable t) {
                    outcome.set(t);
                    interruptedFlag.set(Thread.currentThread().isInterrupted());
                }
            }, "bp-interrupt-owner");
            owner.start();
            assertTrue(entered.await(15, TimeUnit.SECONDS));
            owner.interrupt();
            hold.countDown();
            owner.join(30000);
            assertFalse(owner.isAlive());
            assertTrue(outcome.get() instanceof InterruptedIOException,
                    "expected InterruptedIOException, got " + outcome.get());
            assertTrue(interruptedFlag.get(), "owner interrupt flag must be restored");
            assertEquals(8, permits4.availablePermits(), "permits must return after cancel drain");
        }

        // e) 目录分支同样归还许可；下一次批处理可正常运行。
        Semaphore permits5 = new Semaphore(8, true);
        BatchOptions o5 = fastOptions(null);
        o5.permits = permits5;
        try (LuceneIndexer indexer = new LuceneIndexer(temporary.resolve("share-idx5"), settings(), o5)) {
            indexer.indexFolders(Arrays.asList(s1, s2), false);
        }
        assertEquals(8, permits5.availablePermits());
    }

    private static Path findTxt(Path root) throws IOException {
        try (var stream = Files.walk(root)) {
            return stream.filter(p -> p.toString().endsWith(".txt")).findFirst().orElseThrow();
        }
    }

    @Test
    void shouldWaitForAllWritesBeforeCommitAndClose() throws Exception {
        Path s1 = fileTree("commit-src", 10, 2);
        Path target = findTxt(s1);
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        BatchObserver holdLast = new BatchObserver() {
            @Override public void beforeUpdate(Path path) {
                if (path.equals(target)) {
                    blocked.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        };
        Path indexDir = temporary.resolve("commit-idx");
        LuceneIndexer indexer = new LuceneIndexer(indexDir, settings(), fastOptions(holdLast));
        try {
            AtomicReference<Throwable> outcome = new AtomicReference<>();
            Thread owner = new Thread(() -> {
                try {
                    indexer.indexFolder(s1);
                } catch (Throwable t) {
                    outcome.set(t);
                }
            }, "bp-commit-owner");
            owner.start();
            assertTrue(blocked.await(15, TimeUnit.SECONDS), "last update never blocked");
            // 入口未返回、writer 未关闭、无 commit 可见。
            assertTrue(owner.isAlive(), "indexFolder returned before the last write drained");
            assertTrue(writerOf(indexer).isOpen());
            try (FSDirectory dir = FSDirectory.open(indexDir)) {
                assertFalse(DirectoryReader.indexExists(dir),
                        "no commit may be visible before drain");
            }
            release.countDown();
            owner.join(30000);
            assertNull(outcome.get());
            try (FSDirectory dir = FSDirectory.open(indexDir);
                 DirectoryReader reader = DirectoryReader.open(dir)) {
                assertTrue(reader.numDocs() > 0, "commit must happen after drain");
            }
        } finally {
            release.countDown();
            indexer.close();
        }

        // 拥有者中断：仍等真实 finally，之后 rollback 而非 commit。
        CountDownLatch blocked2 = new CountDownLatch(1);
        CountDownLatch release2 = new CountDownLatch(1);
        AtomicBoolean taskFinished = new AtomicBoolean();
        BatchObserver holdAgain = new BatchObserver() {
            @Override public void beforeUpdate(Path path) {
                if (path.equals(target)) {
                    blocked2.countDown();
                    LuceneIndexerSessionFailureTest.awaitIgnoringInterrupts(release2);
                }
            }
            @Override public void onTaskFinished(Path path) {
                if (path.equals(target)) taskFinished.set(true);
            }
        };
        Path indexDir2 = temporary.resolve("commit-idx2");
        LuceneIndexer indexer2 = new LuceneIndexer(indexDir2, settings(), fastOptions(holdAgain));
        try {
            AtomicReference<Throwable> outcome = new AtomicReference<>();
            AtomicBoolean flagRestored = new AtomicBoolean();
            Thread owner = new Thread(() -> {
                try {
                    indexer2.indexFolder(s1);
                } catch (Throwable t) {
                    outcome.set(t);
                    flagRestored.set(Thread.currentThread().isInterrupted());
                }
            }, "bp-interrupt-commit");
            owner.start();
            assertTrue(blocked2.await(15, TimeUnit.SECONDS));
            owner.interrupt();
            // 任务显式忽略中断：拥有者不能把 cancel/shutdownNow 当作完成屏障。
            assertFalse(taskFinished.get());
            assertTrue(owner.isAlive());
            assertTrue(writerOf(indexer2).isOpen());
            release2.countDown();
            awaitTrue(taskFinished::get, "task finally after explicit release");
            owner.join(30000);
            assertTrue(outcome.get() instanceof InterruptedIOException,
                    "expected InterruptedIOException, got " + outcome.get());
            assertTrue(flagRestored.get(), "interrupt flag restored after drain");
            try (FSDirectory dir = FSDirectory.open(indexDir2)) {
                assertFalse(DirectoryReader.indexExists(dir),
                        "interrupted batch must roll back, not commit");
            }
        } finally {
            release2.countDown();
            indexer2.close();
        }
    }

    @Test
    void shouldHoldCoordinatorUntilParallelWritersDrain() throws Exception {
        Path source = fileTree("coord-src", 16, 2);
        Path indexDir = temporary.resolve("coord-idx");
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch ownerClosed = new CountDownLatch(1);
        CountDownLatch contenderAttempted = new CountDownLatch(1);
        CountDownLatch contenderOpened = new CountDownLatch(1);
        AtomicBoolean taskFinished = new AtomicBoolean();
        AtomicReference<LuceneIndexer> current = new AtomicReference<>();
        AtomicReference<Throwable> ownerOutcome = new AtomicReference<>();
        AtomicReference<Throwable> contenderOutcome = new AtomicReference<>();
        AtomicInteger sequence = new AtomicInteger();
        AtomicInteger firstClosed = new AtomicInteger();
        AtomicInteger secondOpened = new AtomicInteger();
        BatchObserver holdOne = new BatchObserver() {
            private final AtomicBoolean once = new AtomicBoolean();
            @Override public void onTaskStarted(Path path) {
                if (once.compareAndSet(false, true)) blocked.countDown();
                // 全部任务都停在 writer I/O 之前，避免文件通道被中断后 Lucene 自行 tragic-close，
                // 从而使测试只观察拥有者的 close/rollback 屏障。
                boolean flag = false;
                while (true) {
                    try {
                        release.await();
                        break;
                    } catch (InterruptedException e) {
                        flag = true;
                        interrupted.countDown();
                    }
                }
                if (flag) Thread.currentThread().interrupt();
                taskFinished.set(true);
            }
        };
        Thread owner = new Thread(() -> {
            try {
                IndexWriteCoordinator.call(() -> {
                    // writer 的创建、排空与关闭都属于同一个 coordinator 临界区。
                    try (LuceneIndexer indexer = new LuceneIndexer(indexDir, settings(), fastOptions(holdOne))) {
                        current.set(indexer);
                        return indexer.indexFolder(source);
                    } finally {
                        firstClosed.set(sequence.incrementAndGet());
                        ownerClosed.countDown();
                    }
                });
            } catch (Throwable failure) {
                ownerOutcome.set(failure);
            }
        }, "bp-coord-owner");
        Thread contender = new Thread(() -> {
            contenderAttempted.countDown();
            try {
                IndexWriteCoordinator.call(() -> {
                    try (LuceneIndexer indexer = new LuceneIndexer(indexDir, settings(), fastOptions(null))) {
                        secondOpened.set(sequence.incrementAndGet());
                        contenderOpened.countDown();
                        return indexer.indexFolder(source);
                    }
                });
            } catch (Throwable failure) {
                contenderOutcome.set(failure);
            }
        }, "bp-coord-contender");
        try {
            owner.start();
            assertTrue(blocked.await(15, TimeUnit.SECONDS));
            contender.start();
            assertTrue(contenderAttempted.await(15, TimeUnit.SECONDS));
            owner.interrupt();
            assertTrue(interrupted.await(15, TimeUnit.SECONDS), "task must receive and ignore cancellation");
            assertFalse(ownerClosed.await(200, TimeUnit.MILLISECONDS), "close/rollback must wait for real task exit");
            assertEquals(1, contenderOpened.getCount(), "second writer opened before the first writer drained");
            assertTrue(owner.isAlive());
            assertFalse(taskFinished.get());
            assertTrue(writerOf(current.get()).isOpen());
            try (FSDirectory directory = FSDirectory.open(indexDir)) {
                assertFalse(DirectoryReader.indexExists(directory), "cancelled owner has no committed rebuild");
            }
            release.countDown();
            owner.join(30000);
            contender.join(30000);
            assertFalse(owner.isAlive());
            assertFalse(contender.isAlive());
            assertTrue(ownerOutcome.get() instanceof InterruptedIOException, String.valueOf(ownerOutcome.get()));
            assertNull(contenderOutcome.get());
            assertTrue(taskFinished.get());
            assertTrue(secondOpened.get() > firstClosed.get(), "real second writer must open after first close");
        } finally {
            release.countDown();
            owner.interrupt();
            if (owner.getState() != Thread.State.NEW) owner.join(30000);
            if (contender.getState() != Thread.State.NEW) contender.join(30000);
        }
    }

    @Test
    void shouldRollbackRebuildWhenAParallelWriteFails() throws Exception {
        // 旧提交索引在整个失败重建期间始终可读。
        Path old = temporary.resolve("old-source");
        Files.createDirectories(old);
        Path oldFile = old.resolve("old.txt");
        Files.write(oldFile, "legacy committed content".getBytes());
        Path indexDir = temporary.resolve("rollback-idx");
        try (LuceneIndexer indexer = new LuceneIndexer(indexDir, settings(), fastOptions(null))) {
            indexer.indexFolder(old);
        }
        int oldDocs;
        try (FSDirectory dir = FSDirectory.open(indexDir);
             DirectoryReader reader = DirectoryReader.open(dir)) {
            oldDocs = reader.numDocs();
        }
        assertTrue(oldDocs > 0);

        // 恢复证据：重建失败不得清除。
        String tombKey = Utils.normalizeForIndex(oldFile);
        Tombstones.record(tombKey);
        Tombstones.flush();

        Path s1 = fileTree("rebuild-src", 10, 2);
        Path failFile = findTxt(s1);
        Path other = findOtherTxt(s1, failFile);
        CountDownLatch otherCreated = new CountDownLatch(1);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        BatchObserver inject = new BatchObserver() {
            @Override public void onTaskCreated(Path path) {
                if (path.equals(other)) otherCreated.countDown();
            }
            @Override public void onTaskStarted(Path path) {
                // onTaskStarted 在取消检查之前触发：即使注入失败已取消会话，
                // 被堵任务仍可靠地到达此处。
                if (path.equals(other)) {
                    held.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            @Override public void beforeUpdate(Path path) {
                if (path.equals(failFile)) {
                    // 先确保另一任务已被创建（其运行中持有许可被堵住），再注入写失败。
                    try {
                        otherCreated.await(15, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    throw new IllegalStateException("injected write failure");
                }
            }
        };
        LuceneIndexer indexer = new LuceneIndexer(indexDir, settings(), fastOptions(inject));
        try {
            AtomicReference<Throwable> outcome = new AtomicReference<>();
            Thread owner = new Thread(() -> {
                try {
                    indexer.indexFolders(Collections.singletonList(s1), true);
                } catch (Throwable t) {
                    outcome.set(t);
                }
            }, "bp-rebuild-owner");
            owner.start();
            assertTrue(held.await(15, TimeUnit.SECONDS));
            // 卡住另一任务时旧 reader 仍看到旧提交。
            try (FSDirectory dir = FSDirectory.open(indexDir);
                 DirectoryReader reader = DirectoryReader.open(dir)) {
                assertEquals(oldDocs, reader.numDocs(),
                        "old commit must stay visible during the failed rebuild");
            }
            release.countDown();
            owner.join(30000);
            assertTrue(outcome.get() instanceof IOException, "expected IOException, got " + outcome.get());
        } finally {
            release.countDown();
            indexer.close();
        }
        // rollback：旧 reader 仍见旧文档，恢复证据仍在。
        try (FSDirectory dir = FSDirectory.open(indexDir);
             DirectoryReader reader = DirectoryReader.open(dir)) {
            assertEquals(oldDocs, reader.numDocs());
            IndexSearcher searcher = new IndexSearcher(reader);
            assertEquals(1, searcher.count(new TermQuery(new Term("path", tombKey))));
        }
        assertTrue(Tombstones.contains(tombKey),
                "recovery evidence must survive the rolled-back rebuild");
    }

    private static Path findOtherTxt(Path root, Path exclude) throws IOException {
        try (var stream = Files.walk(root)) {
            return stream.filter(p -> p.toString().endsWith(".txt") && !p.equals(exclude))
                    .findFirst().orElseThrow();
        }
    }
}
