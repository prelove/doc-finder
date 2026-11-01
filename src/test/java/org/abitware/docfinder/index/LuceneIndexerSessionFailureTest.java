package org.abitware.docfinder.index;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.abitware.docfinder.index.LuceneIndexer.BatchObserver;
import org.abitware.docfinder.index.LuceneIndexer.BatchOptions;
import org.abitware.docfinder.index.LuceneIndexer.BatchSource;
import org.abitware.docfinder.index.LuceneIndexer.IndexErrorSink.Phase;
import org.abitware.docfinder.util.IsolatedFileAccess;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class LuceneIndexerSessionFailureTest {
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
        Tombstones.close();
        LuceneIndexer.resetExtractionStateForTests();
        System.setProperty("user.dir", originalDirectory);
    }

    private BatchOptions options() {
        BatchOptions options = new BatchOptions();
        options.extractor = (path, maxChars, timeoutMs) ->
                IsolatedFileAccess.Extraction.of("committedtoken", "text/plain", false, true);
        return options;
    }

    private Path source(String name) throws IOException {
        Path root = Files.createDirectories(temporary.resolve(name));
        Files.writeString(root.resolve("sample.txt"), "text");
        return root;
    }

    private int docs(Path index) throws IOException {
        try (FSDirectory directory = FSDirectory.open(index);
             DirectoryReader reader = DirectoryReader.open(directory)) {
            return reader.numDocs();
        }
    }

    private Path committedIndex() throws IOException {
        Path index = temporary.resolve("index");
        try (LuceneIndexer indexer = new LuceneIndexer(index, new IndexSettings(), options())) {
            indexer.indexFolder(source("old"));
        }
        return index;
    }

    @Test
    void shouldRollbackDeleteAllWhenSessionInitializationFails() throws Exception {
        Path index = committedIndex();
        int oldCount = docs(index);
        BatchOptions options = options();
        IllegalStateException failure = new IllegalStateException("executor initialization failed");
        options.executorFactory = () -> { throw failure; };
        try (LuceneIndexer indexer = new LuceneIndexer(index, new IndexSettings(), options)) {
            assertSame(failure, assertThrows(IllegalStateException.class,
                    () -> indexer.indexFolders(Collections.emptyList(), true)));
        }
        assertEquals(oldCount, docs(index), "failed infrastructure must preserve the old commit");
    }

    @Test
    void shouldRollbackDeleteAllWhenSessionInitializationThrowsError() throws Exception {
        Path index = committedIndex();
        BatchOptions options = options();
        AssertionError failure = new AssertionError("executor allocation failed");
        options.executorFactory = () -> { throw failure; };
        try (LuceneIndexer indexer = new LuceneIndexer(index, new IndexSettings(), options)) {
            assertSame(failure, assertThrows(AssertionError.class,
                    () -> indexer.indexFolders(Collections.emptyList(), true)));
        }
        assertEquals(2, docs(index));
    }

    @Test
    void shouldReleaseRegisteredTaskWhenPreparationFails() throws Exception {
        Path file = source("registration").resolve("sample.txt");
        BatchOptions options = options();
        options.permits = new Semaphore(8, true);
        options.observer = new BatchObserver() {
            @Override public void onTaskCreated(Path path) {
                throw new IllegalStateException("after phaser registration");
            }
        };
        try (LuceneIndexer indexer = new LuceneIndexer(temporary.resolve("registration-index"),
                new IndexSettings(), options)) {
            LuceneIndexer.BatchIndexSession session = indexer.new BatchIndexSession();
            BatchSource source = new BatchSource(file.getParent());
            try {
                try {
                    session.dispatchFile(source, file, Files.readAttributes(file, BasicFileAttributes.class));
                } catch (IllegalStateException expectedOnOldImplementation) {
                    // 首先检查真实许可与 party；旧实现会把二者遗留在无人接管的窗口。
                }
                assertEquals(8, options.permits.availablePermits());
                assertEquals(1, source.registeredParties());
                assertThrows(IOException.class, session::finishDrain);
            } finally {
                session.close();
            }
        }
    }

    @Test
    void shouldCompleteUnacceptedSourceAfterSourcePreparationFails() throws Exception {
        Path index = committedIndex();
        AtomicReference<BatchSource> submitted = new AtomicReference<>();
        BatchOptions options = options();
        options.observer = new BatchObserver() {
            @Override public void onSourceSubmitted(BatchSource source) {
                submitted.set(source);
                throw new AssertionError("source preparation failed");
            }
        };
        try (LuceneIndexer indexer = new LuceneIndexer(index, new IndexSettings(), options)) {
            assertThrows(AssertionError.class, () -> indexer.indexFolders(
                    Arrays.asList(source("new1"), source("new2")), true));
        }
        assertEquals(0, submitted.get().pendingDone());
        assertEquals(0, submitted.get().registeredParties());
        assertEquals(2, docs(index));
    }

    @Test
    void shouldPropagateErrorEvenAfterAnEarlierOrdinaryWriteFailure() throws Exception {
        try (LuceneIndexer indexer = new LuceneIndexer(temporary.resolve("error-index"),
                new IndexSettings(), options())) {
            LuceneIndexer.BatchIndexSession session = indexer.new BatchIndexSession();
            AssertionError fatal = new AssertionError("later fatal Error");
            try {
                session.onError(null, Phase.WRITE, new IOException("first ordinary write failure"));
                session.onError(null, Phase.EXTRACT, fatal);
                assertSame(fatal, assertThrows(AssertionError.class, session::finishDrain));
            } finally {
                session.close();
            }
        }
    }

    @Test
    void shouldSummarizeAllDegradationsWithBoundedSamples() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(LuceneIndexer.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        logger.addAppender(events);
        try (LuceneIndexer indexer = new LuceneIndexer(temporary.resolve("summary-index"),
                new IndexSettings(), options())) {
            LuceneIndexer.BatchIndexSession session = indexer.new BatchIndexSession();
            try {
                for (int i = 0; i < 40; i++) {
                    session.onError(temporary.resolve("bad" + i), Phase.EXTRACT, new IOException("parse " + i));
                }
                session.finishDrain();
                var summaries = events.list.stream().map(ILoggingEvent::getFormattedMessage)
                        .filter(message -> message.contains("EXTRACT=40")).toList();
                assertEquals(1, summaries.size(), "one classified end-of-session summary");
                assertTrue(summaries.getFirst().contains("samples=32"), "sample storage must stay bounded");
                indexer.commit();
            } finally {
                session.close();
            }
        } finally {
            logger.detachAppender(events);
            events.stop();
        }
    }

    @Test
    void shouldIncludeCategoryTotalsAndFirstCauseInFatalException() throws Exception {
        try (LuceneIndexer indexer = new LuceneIndexer(temporary.resolve("fatal-summary-index"),
                new IndexSettings(), options())) {
            LuceneIndexer.BatchIndexSession session = indexer.new BatchIndexSession();
            IOException first = new IOException("first write failure");
            try {
                session.onError(null, Phase.WRITE, first);
                for (int i = 1; i < 40; i++) session.onError(null, Phase.WRITE, new IOException("write " + i));
                IOException failure = assertThrows(IOException.class, session::finishDrain);
                assertSame(first, failure.getCause());
                assertTrue(failure.getMessage().contains("WRITE=40"));
                assertTrue(failure.getMessage().contains("first write failure"));
                assertEquals(31, failure.getSuppressed().length);
            } finally {
                session.close();
            }
        }
    }

    @Test
    void shouldSummarizeDegradationsWhenOwnerPathNormalizationFails() throws Exception {
        assertOwnerFailureSummary(new IllegalStateException("owner normalization failure"), false);
    }

    @Test
    void shouldPreserveOwnerErrorWithDegradationSummary() throws Exception {
        assertOwnerFailureSummary(new AssertionError("owner normalization Error"), false);
    }

    @Test
    void shouldPreferTaskErrorWhenOwnerAlsoFailsAfterDrain() throws Exception {
        assertOwnerFailureSummary(new IllegalStateException("owner after-drain failure"), true);
    }

    private void assertOwnerFailureSummary(Throwable ownerFailure, boolean taskError) throws Exception {
        Path index = committedIndex();
        long oldGeneration;
        try (FSDirectory directory = FSDirectory.open(index);
             DirectoryReader reader = DirectoryReader.open(directory)) {
            oldGeneration = reader.getIndexCommit().getGeneration();
        }
        Path root = Files.createDirectory(temporary.resolve("degraded-source"));
        for (int i = 0; i < 40; i++) Files.writeString(root.resolve("bad" + i + ".txt"), "text");
        BatchOptions options = options();
        options.extractor = (path, maxChars, timeoutMs) -> { throw new IOException("degraded " + path); };
        AssertionError fatal = new AssertionError("task Error after degradations");
        if (taskError) {
            // 全部提取降级完成后才在任务 finally 抛 Error；随后拥有者回调也失败。
            options.scheduling = LuceneIndexer.Scheduling.SERIAL;
            java.util.concurrent.atomic.AtomicInteger finished = new java.util.concurrent.atomic.AtomicInteger();
            options.observer = new BatchObserver() {
                @Override public void onTaskFinished(Path path) {
                    if (path.toString().endsWith(".txt") && finished.incrementAndGet() == 40) throw fatal;
                }
                @Override public void onBatchDrained() { throw (RuntimeException) ownerFailure; }
            };
        }
        Path failingPath = (Path) java.lang.reflect.Proxy.newProxyInstance(
                Path.class.getClassLoader(), new Class<?>[]{Path.class},
                (proxy, method, arguments) -> { throw ownerFailure; });
        Logger logger = (Logger) LoggerFactory.getLogger(LuceneIndexer.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        logger.addAppender(events);
        Throwable thrown;
        try (LuceneIndexer indexer = new LuceneIndexer(index, new IndexSettings(), options)) {
            // 重叠根迫使第一批完成，之后才检查 failingPath，确保不是零错误会话。
            thrown = assertThrows(Throwable.class, () -> indexer.indexFolders(taskError
                    ? Collections.singletonList(root) : Arrays.asList(root, root, failingPath), true));
        } finally {
            logger.detachAppender(events);
            events.stop();
        }
        assertSame(taskError ? fatal : ownerFailure, thrown);
        if (taskError) assertTrue(Arrays.asList(thrown.getSuppressed()).contains(ownerFailure));
        var summaries = events.list.stream().filter(event -> event.getFormattedMessage()
                .startsWith("Index batch summary:")).toList();
        assertEquals(1, summaries.size(), "owner failure must report exactly one end summary");
        String totals = "WALK=0, EXTRACT=40, WRITE=" + (taskError ? 1 : 0);
        assertTrue(summaries.getFirst().getFormattedMessage().contains(totals));
        assertTrue(summaries.getFirst().getFormattedMessage().contains("samples=32"));
        assertEquals(32, ((java.util.List<?>) summaries.getFirst().getArgumentArray()[3]).size());
        Throwable firstCause = (Throwable) summaries.getFirst().getArgumentArray()[2];
        if (taskError) assertSame(fatal, firstCause);
        else assertTrue(firstCause.getMessage().startsWith("degraded " + root));
        var contexts = Arrays.stream(thrown.getSuppressed()).filter(failure -> failure instanceof IOException
                && failure.getMessage().contains(totals)).toList();
        assertEquals(1, contexts.size(), "original throwable must carry one classified context");
        assertTrue(contexts.getFirst().getMessage().contains("samples=32"));
        assertTrue(contexts.getFirst().getMessage().contains("first cause=" + firstCause));
        assertSame(taskError ? null : firstCause, contexts.getFirst().getCause());
        assertEquals(31, contexts.getFirst().getSuppressed().length, "exception samples must also stay bounded");
        assertTrue(Arrays.stream(contexts.getFirst().getSuppressed())
                .allMatch(sample -> sample.getMessage().contains("EXTRACT failure at " + root)));
        try (FSDirectory directory = FSDirectory.open(index);
             DirectoryReader reader = DirectoryReader.open(directory)) {
            assertEquals(oldGeneration, reader.getIndexCommit().getGeneration(), "no new commit on failure");
            assertEquals(2, reader.numDocs(), "old committed documents survive full rebuild rollback");
        }
    }

    @Test
    void shouldReleasePermitWhenFailureOccursBeforePhaserRegistration() throws Exception {
        BatchOptions options = options();
        options.permits = new Semaphore(8, true);
        options.observer = new BatchObserver() {
            public void onPermitAcquired(Path path) {
                throw new IllegalStateException("after acquire, before register");
            }
        };
        Path index = committedIndex();
        try (LuceneIndexer indexer = new LuceneIndexer(index, new IndexSettings(), options)) {
            assertThrows(IOException.class, () -> indexer.indexFolders(Collections.singletonList(source("new")), true));
        }
        assertEquals(8, options.permits.availablePermits());
        assertEquals(2, docs(index));
    }

    @Test
    void shouldCompleteTaskAccountingWhenRunningThreadRegistrationThrowsError() throws Exception {
        Path file = source("entry").resolve("sample.txt");
        BatchOptions options = options();
        options.permits = new Semaphore(8, true);
        try (LuceneIndexer indexer = new LuceneIndexer(temporary.resolve("entry-index"),
                new IndexSettings(), options)) {
            LuceneIndexer.BatchIndexSession session = indexer.new BatchIndexSession();
            AssertionError fatal = new AssertionError("running thread registration");
            injectFailingThreadSet(session, fatal);
            BatchSource source = new BatchSource(file.getParent());
            try {
                session.dispatchFile(source, file, Files.readAttributes(file, BasicFileAttributes.class));
                session.executor.shutdown();
                assertTrue(session.executor.awaitTermination(10, TimeUnit.SECONDS));
                assertEquals(8, options.permits.availablePermits());
                assertEquals(1, source.registeredParties());
                assertSame(fatal, assertThrows(AssertionError.class, session::finishDrain));
            } finally {
                session.close();
            }
        }
    }

    @Test
    void shouldCompleteSourceAccountingWhenRunningThreadRegistrationThrowsError() throws Exception {
        try (LuceneIndexer indexer = new LuceneIndexer(temporary.resolve("source-entry-index"),
                new IndexSettings(), options())) {
            LuceneIndexer.BatchIndexSession session = indexer.new BatchIndexSession();
            AssertionError fatal = new AssertionError("source thread registration");
            injectFailingThreadSet(session, fatal);
            try {
                BatchSource source = session.submitSource(source("source-entry"));
                session.executor.shutdown();
                assertTrue(session.executor.awaitTermination(10, TimeUnit.SECONDS));
                assertEquals(0, source.pendingDone());
                assertEquals(1, source.registeredParties());
                assertSame(fatal, assertThrows(AssertionError.class, session::finishDrain));
            } finally {
                session.close();
            }
        }
    }

    private static void injectFailingThreadSet(LuceneIndexer.BatchIndexSession session, Error failure)
            throws ReflectiveOperationException {
        var field = session.getClass().getDeclaredField("runningThreads");
        field.setAccessible(true);
        field.set(session, new java.util.AbstractSet<Thread>() {
            @Override public java.util.Iterator<Thread> iterator() { return Collections.emptyIterator(); }
            @Override public int size() { return 0; }
            @Override public boolean add(Thread thread) { throw failure; }
        });
    }

    @Test
    void shouldDrainUninterruptibleTaskBeforePropagatingSourceFailureAndClosingWriter() throws Exception {
        Path index = committedIndex();
        Path first = source("first-source");
        Path second = source("second-source");
        Path blockedFile = first.resolve("sample.txt");
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch taskFinished = new CountDownLatch(1);
        CountDownLatch ownerDone = new CountDownLatch(1);
        AssertionError fatal = new AssertionError("second source preparation failed");
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        AtomicReference<LuceneIndexer> active = new AtomicReference<>();
        BatchOptions options = options();
        options.observer = new BatchObserver() {
            @Override public void onSourceSubmitted(BatchSource source) {
                if (source.root.equals(second)) {
                    awaitIgnoringInterrupts(blocked);
                    throw fatal;
                }
            }
            @Override public void beforeUpdate(Path path) {
                // 所有写任务都停在 I/O 之前，避免取消时其它目录写入被中断而关闭 Lucene。
                // 本测试观察拥有者排空屏障，而不是文件通道的中断语义。
                if (path.equals(blockedFile)) blocked.countDown();
                while (true) {
                    try {
                        release.await();
                        return;
                    } catch (InterruptedException ignored) {
                        if (path.equals(blockedFile)) interrupted.countDown();
                    }
                }
            }
            @Override public void onTaskFinished(Path path) {
                if (path.equals(blockedFile)) taskFinished.countDown();
            }
        };
        Thread owner = new Thread(() -> {
            try (LuceneIndexer indexer = new LuceneIndexer(index, new IndexSettings(), options)) {
                active.set(indexer);
                indexer.indexFolders(Arrays.asList(first, second), true);
            } catch (Throwable failure) {
                outcome.set(failure);
            } finally {
                ownerDone.countDown();
            }
        }, "source-failure-owner");
        try {
            owner.start();
            assertTrue(blocked.await(15, TimeUnit.SECONDS));
            assertTrue(interrupted.await(15, TimeUnit.SECONDS), "accepted task must observe cancellation");
            assertFalse(ownerDone.await(200, TimeUnit.MILLISECONDS), "owner/close must wait for ignored interrupts");
            var field = LuceneIndexer.class.getDeclaredField("writer");
            field.setAccessible(true);
            assertTrue(((org.apache.lucene.index.IndexWriter) field.get(active.get())).isOpen());
            assertEquals(2, docs(index), "old commit remains readable until rollback");
            release.countDown();
            assertTrue(ownerDone.await(30, TimeUnit.SECONDS));
            assertSame(fatal, outcome.get());
            assertEquals(2, docs(index));
        } finally {
            release.countDown();
            owner.join(30000);
            assertTrue(taskFinished.await(15, TimeUnit.SECONDS));
            assertFalse(owner.isAlive());
        }
    }

    @Test
    void shouldPropagateLaterErrorFromARealTaskAfterOrdinaryWriteFailure() throws Exception {
        Path index = committedIndex();
        Path root = source("two-failures");
        Path ordinary = root.resolve("sample.txt");
        Path later = root.resolve("later.txt");
        Files.writeString(later, "text");
        CountDownLatch secondEntered = new CountDownLatch(1);
        CountDownLatch firstFinished = new CountDownLatch(1);
        AssertionError fatal = new AssertionError("fatal task after ordinary failure");
        BatchOptions options = options();
        options.observer = new BatchObserver() {
            @Override public void beforeBuild(Path path) {
                if (path.equals(ordinary)) {
                    awaitIgnoringInterrupts(secondEntered);
                    throw new IllegalStateException("ordinary write failure first");
                }
                if (path.equals(later)) {
                    secondEntered.countDown();
                    awaitIgnoringInterrupts(firstFinished);
                    throw fatal;
                }
            }
            @Override public void onTaskFinished(Path path) {
                if (path.equals(ordinary)) firstFinished.countDown();
            }
        };
        try (LuceneIndexer indexer = new LuceneIndexer(index, new IndexSettings(), options)) {
            assertSame(fatal, assertThrows(AssertionError.class,
                    () -> indexer.indexFolders(Collections.singletonList(root), true)));
        }
        assertEquals(2, docs(index));
        assertEquals(8, LuceneIndexer.INDEX_TASK_PERMITS.availablePermits());
    }

    @Test
    void shouldStopSubmittingLaterSourcesAfterFatalRejection() throws Exception {
        BatchOptions options = options();
        java.util.List<BatchSource> observed = new java.util.ArrayList<>();
        options.observer = new BatchObserver() {
            @Override public void onSourceSubmitted(BatchSource source) { observed.add(source); }
        };
        options.executorFactory = () -> new java.util.concurrent.AbstractExecutorService() {
            @Override public void shutdown() { }
            @Override public java.util.List<Runnable> shutdownNow() { return Collections.emptyList(); }
            @Override public boolean isShutdown() { return true; }
            @Override public boolean isTerminated() { return true; }
            @Override public boolean awaitTermination(long time, TimeUnit unit) { return true; }
            @Override public void execute(Runnable task) {
                throw new java.util.concurrent.RejectedExecutionException("source rejected");
            }
        };
        Path first = source("first-rejected");
        Path second = source("must-not-submit");
        try (LuceneIndexer indexer = new LuceneIndexer(temporary.resolve("rejected-index"),
                new IndexSettings(), options)) {
            assertThrows(IOException.class, () -> indexer.indexFolders(Arrays.asList(first, second), false));
        }
        assertEquals(1, observed.size(), "fatal first source must prevent later source construction");
        assertEquals(0, observed.getFirst().pendingDone());
        assertEquals(0, observed.getFirst().registeredParties());
    }

    static void awaitIgnoringInterrupts(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
