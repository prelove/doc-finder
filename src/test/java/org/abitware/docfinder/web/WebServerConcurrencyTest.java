package org.abitware.docfinder.web;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class WebServerConcurrencyTest {

    @Test
    void shouldRunExchangeTasksOnVirtualThreads() throws Exception {
        WebServer.BoundedRequestExecutor executor = new WebServer.BoundedRequestExecutor();
        try {
            CountDownLatch ran = new CountDownLatch(1);
            AtomicBoolean virtual = new AtomicBoolean();
            AtomicReference<String> name = new AtomicReference<>();
            executor.execute(() -> {
                virtual.set(Thread.currentThread().isVirtual());
                name.set(Thread.currentThread().getName());
                ran.countDown();
            });
            assertTrue(ran.await(10, TimeUnit.SECONDS));
            assertTrue(virtual.get(), "exchange tasks must run on virtual threads");
            assertTrue(name.get().startsWith("docfinder-web-"), name.get());
        } finally {
            executor.shutdownNow();
        }
        // executor termination 包含 Admission.run 的 finally，exchange 内的 latch 没有此保证。
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals(0, executor.activeCount());
        assertEquals(0, executor.admittedCount());
    }

    @Test
    void shouldBoundActiveAndAdmittedTasks() throws Exception {
        WebServer.BoundedRequestExecutor executor = new WebServer.BoundedRequestExecutor();
        CountDownLatch started = new CountDownLatch(WebServer.MAX_CONCURRENT_REQUESTS);
        CountDownLatch release = new CountDownLatch(1);
        try {
            for (int i = 0; i < WebServer.MAX_CONCURRENT_REQUESTS; i++) {
                executor.execute(() -> {
                    started.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            // latch 确认 8 个执行动作已进入并阻塞，执行槽占满。
            assertTrue(started.await(10, TimeUnit.SECONDS));
            assertEquals(WebServer.MAX_CONCURRENT_REQUESTS, executor.activeCount());

            // 再接收 128 个等待任务，合计达到 136 的接收上限。
            for (int i = 0; i < WebServer.MAX_PENDING_REQUESTS; i++) {
                executor.execute(() -> { });
            }
            assertEquals(WebServer.MAX_ADMITTED_REQUESTS, executor.admittedCount());

            // 第 137 个同步拒绝，且不创建新任务。
            AtomicBoolean ran = new AtomicBoolean();
            assertThrows(RejectedExecutionException.class,
                    () -> executor.execute(() -> ran.set(true)));
            assertFalse(ran.get());
            assertEquals(WebServer.MAX_ADMITTED_REQUESTS, executor.admittedCount());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals(0, executor.activeCount());
        assertEquals(0, executor.admittedCount());
    }

    @Test
    void shouldBoundOnlyAdmissionWhenTheDelegateIsDelayed() throws Exception {
        // 延迟调度接缝：delegate 单线程被占住时，已接收任务堆在 delegate
        // 队列里。此时只断言 admitted ≤ 136 与 active ≤ 8，不断言瞬时待调度数。
        ThreadPoolExecutor delegate = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(), runnable -> {
            Thread thread = new Thread(runnable, "test-delegate");
            thread.setDaemon(true);
            return thread;
        });
        WebServer.BoundedRequestExecutor executor =
                new WebServer.BoundedRequestExecutor(delegate, 8, 136);
        CountDownLatch delegateBlock = new CountDownLatch(1);
        CountDownLatch delegateBusy = new CountDownLatch(1);
        try {
            delegate.execute(() -> {
                delegateBusy.countDown();
                try {
                    delegateBlock.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(delegateBusy.await(10, TimeUnit.SECONDS));
            for (int i = 0; i < 136; i++) {
                executor.execute(() -> { });
            }
            assertEquals(136, executor.admittedCount());
            assertTrue(executor.activeCount() <= 8);
            assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> { }));
        } finally {
            delegateBlock.countDown();
            executor.shutdownNow();
            delegate.shutdownNow();
        }
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        // shutdownNow 返还的未启动任务 lease 已一次性归还。
        assertEquals(0, executor.admittedCount());
    }

    @Test
    void shouldReleasePermitsAfterFailureAndInterruption() throws Exception {
        WebServer.BoundedRequestExecutor executor = new WebServer.BoundedRequestExecutor();
        CountDownLatch blockersStarted = new CountDownLatch(WebServer.MAX_CONCURRENT_REQUESTS);
        try {
            // 抛异常的 exchange：finally 必须归还两级许可。
            executor.execute(() -> { throw new IllegalStateException("exchange boom"); });
            waitFor(() -> executor.admittedCount() == 0 && executor.activeCount() == 0);

            CountDownLatch hold = new CountDownLatch(1);
            for (int i = 0; i < WebServer.MAX_CONCURRENT_REQUESTS; i++) {
                executor.execute(() -> {
                    blockersStarted.countDown();
                    try {
                        hold.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            assertTrue(blockersStarted.await(10, TimeUnit.SECONDS));
            // 一个等待执行槽的任务：shutdownNow 中断其 active.acquire()，
            // 不运行 exchange，只归还 admitted。
            AtomicBoolean waiterRan = new AtomicBoolean();
            executor.execute(() -> waiterRan.set(true));
            waitFor(() -> executor.admittedCount() == WebServer.MAX_CONCURRENT_REQUESTS + 1);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            assertFalse(waiterRan.get());
            assertEquals(0, executor.admittedCount());
            assertEquals(0, executor.activeCount());
        } finally {
            executor.shutdownNow();
        }

        // delegate 拒绝时由提交方归还许可。
        ExecutorService rejecting = new AbstractExecutorService() {
            @Override public void shutdown() { }
            @Override public List<Runnable> shutdownNow() { return new ArrayList<>(); }
            @Override public boolean isShutdown() { return false; }
            @Override public boolean isTerminated() { return false; }
            @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
            @Override public void execute(Runnable command) {
                throw new RejectedExecutionException("delegate full");
            }
        };
        WebServer.BoundedRequestExecutor bounded =
                new WebServer.BoundedRequestExecutor(rejecting, 8, 136);
        assertThrows(RejectedExecutionException.class, () -> bounded.execute(() -> { }));
        assertEquals(0, bounded.admittedCount());
        assertEquals(0, bounded.activeCount());
    }

    @Test
    void shouldRejectNewTasksAfterShutdown() throws Exception {
        WebServer.BoundedRequestExecutor executor = new WebServer.BoundedRequestExecutor();
        executor.shutdownNow();
        assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> { }));
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        assertTrue(executor.isTerminated());
        assertEquals(0, executor.admittedCount());
    }

    @Test
    void shouldReleaseAdmissionWhenDelegateThrowsError() {
        AssertionError failure = new AssertionError("thread creation failed");
        ExecutorService delegate = new ThrowingExecutor(failure, false);
        WebServer.BoundedRequestExecutor executor = new WebServer.BoundedRequestExecutor(delegate, 1, 1);
        assertSame(failure, assertThrows(AssertionError.class, () -> executor.execute(() -> { })));
        assertEquals(0, executor.admittedCount(), "submission Error must return its admission lease");
        assertEquals(0, executor.activeCount());
    }

    @Test
    void shouldReleaseAdmissionExactlyOnceWhenDelegateRunsThenThrows() {
        AssertionError failure = new AssertionError("failure after execution");
        WebServer.BoundedRequestExecutor executor = new WebServer.BoundedRequestExecutor(
                new ThrowingExecutor(failure, true), 1, 1);
        AtomicInteger completed = new AtomicInteger();
        assertSame(failure, assertThrows(AssertionError.class, () -> executor.execute(completed::incrementAndGet)));
        assertEquals(1, completed.get());
        assertEquals(0, executor.admittedCount(), "task and submitter cleanup must share one lease");
        assertEquals(0, executor.activeCount());
    }

    @Test
    void shouldReturnBothLeasesBeforeTheDelegateSignalsCompletion() throws Exception {
        CountDownLatch completed = new CountDownLatch(1);
        ExecutorService workers = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory());
        ExecutorService delegate = new AbstractExecutorService() {
            @Override public void execute(Runnable task) {
                workers.execute(() -> {
                    try { task.run(); }
                    finally { completed.countDown(); }
                });
            }
            @Override public void shutdown() { workers.shutdown(); }
            @Override public List<Runnable> shutdownNow() { return workers.shutdownNow(); }
            @Override public boolean isShutdown() { return workers.isShutdown(); }
            @Override public boolean isTerminated() { return workers.isTerminated(); }
            @Override public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
                return workers.awaitTermination(timeout, unit);
            }
        };
        WebServer.BoundedRequestExecutor executor = new WebServer.BoundedRequestExecutor(delegate, 1, 1);
        AtomicInteger runs = new AtomicInteger();
        try {
            executor.execute(runs::incrementAndGet);
            assertTrue(completed.await(10, TimeUnit.SECONDS), "completion must follow the Admission wrapper finally");
            assertEquals(1, runs.get());
            assertEquals(0, executor.activeCount());
            assertEquals(0, executor.admittedCount());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void shouldReleaseAdmissionWhenConstructionFails() throws Exception {
        WebServer.BoundedRequestExecutor executor = new WebServer.BoundedRequestExecutor();
        try {
            AssertionError error = new AssertionError("admission allocation");
            executor.admissionFailpoint = () -> { throw error; };
            assertSame(error, assertThrows(AssertionError.class, () -> executor.execute(() -> { })));
            assertEquals(0, executor.admittedCount());
            IllegalStateException runtime = new IllegalStateException("admission setup");
            executor.admissionFailpoint = () -> { throw runtime; };
            assertSame(runtime, assertThrows(IllegalStateException.class, () -> executor.execute(() -> { })));
            assertEquals(0, executor.admittedCount());
            executor.admissionFailpoint = null;
            CountDownLatch ran = new CountDownLatch(1);
            executor.execute(ran::countDown);
            assertTrue(ran.await(10, TimeUnit.SECONDS), "construction failure must preserve capacity");
        } finally {
            executor.shutdownNow();
        }
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals(0, executor.admittedCount());
        assertEquals(0, executor.activeCount());
    }

    @Test
    void shouldReleaseAdmissionExactlyOnceWhenShutdownRacesSubmissionFailure() throws Exception {
        CountDownLatch submitted = new CountDownLatch(1);
        CountDownLatch releaseSubmitter = new CountDownLatch(1);
        AtomicReference<Runnable> pending = new AtomicReference<>();
        ExecutorService delegate = new AbstractExecutorService() {
            @Override public void execute(Runnable task) {
                pending.set(task);
                submitted.countDown();
                try { releaseSubmitter.await(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                throw new RejectedExecutionException("shutdown won the race");
            }
            @Override public void shutdown() { }
            @Override public List<Runnable> shutdownNow() {
                Runnable task = pending.getAndSet(null);
                return task == null ? java.util.Collections.emptyList() : java.util.Collections.singletonList(task);
            }
            @Override public boolean isShutdown() { return true; }
            @Override public boolean isTerminated() { return true; }
            @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
        };
        WebServer.BoundedRequestExecutor executor = new WebServer.BoundedRequestExecutor(delegate, 1, 1);
        AtomicReference<Throwable> result = new AtomicReference<>();
        CountDownLatch completed = new CountDownLatch(1);
        Thread submitter = Thread.ofVirtual().start(() -> {
            try { executor.execute(() -> fail("shutdown task must never run")); }
            catch (Throwable failure) { result.set(failure); }
            finally { completed.countDown(); }
        });
        try {
            assertTrue(submitted.await(10, TimeUnit.SECONDS));
            assertEquals(1, executor.admittedCount());
            executor.shutdownNow();
            assertEquals(0, executor.admittedCount(), "shutdown returns the queued task's lease");
            releaseSubmitter.countDown();
            assertTrue(completed.await(10, TimeUnit.SECONDS));
            assertInstanceOf(RejectedExecutionException.class, result.get());
            assertEquals(0, executor.admittedCount(), "submission cleanup must not return it a second time");
            assertEquals(0, executor.activeCount());
        } finally {
            releaseSubmitter.countDown();
            submitter.join(10000);
            assertFalse(submitter.isAlive());
            executor.shutdownNow();
        }
    }

    private static final class ThrowingExecutor extends AbstractExecutorService {
        private final Error failure;
        private final boolean runFirst;
        ThrowingExecutor(Error failure, boolean runFirst) {
            this.failure = failure;
            this.runFirst = runFirst;
        }
        @Override public void execute(Runnable command) {
            if (runFirst) command.run();
            throw failure;
        }
        @Override public void shutdown() { }
        @Override public List<Runnable> shutdownNow() { return new ArrayList<>(); }
        @Override public boolean isShutdown() { return true; }
        @Override public boolean isTerminated() { return true; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
    }

    private static void waitFor(Check check) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (check.holds()) return;
            Thread.sleep(10);
        }
        assertTrue(check.holds(), "condition not reached within 10 s");
    }

    private interface Check {
        boolean holds();
    }
}
