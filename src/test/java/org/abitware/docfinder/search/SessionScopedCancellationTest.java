package org.abitware.docfinder.search;

import org.abitware.docfinder.index.IndexSettings;
import org.abitware.docfinder.index.LuceneIndexer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Cancellation is scoped to the caller, independently of source I/O scheduling. */
@Execution(ExecutionMode.SAME_THREAD)
class SessionScopedCancellationTest {
    @TempDir Path fixtureRoot;
    private LuceneSearchService service;
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);

    @BeforeEach
    void setUp() throws Exception {
        Path source = Files.createDirectory(fixtureRoot.resolve("source"));
        Path index = fixtureRoot.resolve("index");
        IndexSettings settings = new IndexSettings();
        settings.includeExt = Collections.emptyList();
        settings.parseTextLike = false;
        Path file = Files.write(source.resolve("lorem.txt"), new byte[] { 1 });
        try (LuceneIndexer writer = new LuceneIndexer(index, settings)) { writer.indexFolder(source); }
        Files.delete(file);
        // Keep JVM startup latency outside the cancellation assertion: this
        // fixture tests caller sequencing, not the metadata worker deadline.
        for (int i = 0; i < 2; i++) {
            assertEquals(org.abitware.docfinder.util.IsolatedFileAccess.Classification.GHOST,
                    org.abitware.docfinder.util.IsolatedFileAccess.classify(file.toString(),
                            org.abitware.docfinder.util.PathScope.normalizeIndexedPath(source), 10000));
        }
        service = new LuceneSearchService(index, settings, () -> {
            // The constructor and successor proceed; only the named old caller waits.
            if ("blocked-search".equals(Thread.currentThread().getName())) {
                entered.countDown();
                try {
                    if (!release.await(30, TimeUnit.SECONDS)) throw new IOException("Test source gate timed out");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Test source gate interrupted", e);
                }
            }
            return Collections.singletonList(source);
        });
    }

    @AfterEach
    void tearDown() {
        release.countDown();
        if (service != null) service.close();
    }

    @Test
    void shouldNotCancelAcrossDifferentSessions() throws Exception {
        assertConcurrentOutcome("client-A", "client-B", TriState.FALSE);
    }

    @Test
    void shouldCancelWithinSameSessionWithoutFakingComplete() throws Exception {
        assertConcurrentOutcome("client-A", "client-A", TriState.UNKNOWN);
    }

    @Test
    void shouldGiveNullTagRequestsPrivateSessions() throws Exception {
        assertConcurrentOutcome(null, null, TriState.FALSE);
    }

    private void assertConcurrentOutcome(String oldTag, String successorTag, TriState expected) throws Exception {
        AtomicReference<SearchOutcome> oldOutcome = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread old = new Thread(() -> {
            try { oldOutcome.set(service.searchDetailed(request(oldTag))); }
            catch (Throwable e) { failure.set(e); }
        }, "blocked-search");
        old.start();
        try {
            assertTrue(entered.await(20, TimeUnit.SECONDS), "old request must be in progress before its successor starts");
            SearchOutcome successor = service.searchDetailed(request(successorTag));
            assertEquals(TriState.FALSE, successor.hasMore, "successor examines the missing name hit to its tail");
        } finally {
            release.countDown();
            old.join(30_000);
        }
        assertFalse(old.isAlive(), "old request must finish after its gate opens");
        assertNull(failure.get(), "old request must finish normally");
        assertNotNull(oldOutcome.get());
        assertEquals(expected, oldOutcome.get().hasMore,
                "only a successor with the same non-null session may invalidate an in-flight request");
    }

    private static SearchRequest request(String tag) {
        return new SearchRequest("lorem", 50, null, SearchScope.NAME, MatchMode.FUZZY, null, tag);
    }
}
