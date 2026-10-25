package org.abitware.docfinder.search;

import org.abitware.docfinder.index.IndexSettings;
import org.abitware.docfinder.index.LuceneIndexer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RG-04 regression: cancellation must be scoped to the caller session.
 * A newer search may only supersede in-flight work of the SAME client —
 * a different client (another web caller, or desktop vs web) must never
 * abort a concurrent request, and a real cancel must not be reported as
 * a fabricated complete page (UNKNOWN, not FALSE).
 */
@Execution(ExecutionMode.SAME_THREAD)
class SessionScopedCancellationTest {

    @TempDir
    Path fixtureRoot;

    private Path source;
    private Path index;
    private IndexSettings settings;
    private LuceneSearchService service;

    @BeforeEach
    void setUp() throws Exception {
        source = fixtureRoot.resolve("source");
        index = fixtureRoot.resolve("index");
        Files.createDirectories(source);
        settings = new IndexSettings();
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.close();
        }
    }

    private static void write(Path p, String content) throws Exception {
        Files.write(p, content.getBytes("UTF-8"));
    }

    private void indexAndOpen(String... names) throws Exception {
        try (LuceneIndexer idx = new LuceneIndexer(index, settings)) {
            idx.indexFolder(source);
            for (String n : names) {
                idx.upsertFile(source.resolve(n));
            }
        }
        service = new LuceneSearchService(index, settings,
                () -> Collections.singletonList(source));
    }

    private static SearchRequest req(String tag) {
        return new SearchRequest("lorem", 50, null, SearchScope.ALL,
                MatchMode.FUZZY, null, tag);
    }

    /**
     * Occupies all EXISTENCE_POOL workers so every classifyPath stat blocks
     * and times out (~250ms each) — giving a multi-candidate search a long,
     * deterministic in-flight window without touching the filesystem.
     */
    private static CountDownLatch blockExistencePool() {
        CountDownLatch release = new CountDownLatch(1);
        for (int i = 0; i < 4; i++) {
            LuceneSearchService.EXISTENCE_POOL.execute(() -> {
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        return release;
    }

    @Test
    void shouldNotCancelAcrossDifferentSessions() throws Exception {
        write(source.resolve("a1.txt"), "lorem");
        write(source.resolve("a2.txt"), "lorem");
        write(source.resolve("a3.txt"), "lorem");
        indexAndOpen();
        Files.delete(source.resolve("a1.txt"));
        Files.delete(source.resolve("a2.txt"));
        Files.delete(source.resolve("a3.txt"));

        CountDownLatch release = blockExistencePool();
        AtomicReference<SearchOutcome> aOut = new AtomicReference<>();
        try {
            Thread a = new Thread(() -> aOut.set(service.searchDetailed(req("client-A"))));
            a.start();
            Thread.sleep(150); // let A enter its refill loop (~250ms per stat)

            SearchOutcome b = service.searchDetailed(req("client-B"));
            a.join(30_000);

            assertEquals(TriState.FALSE, b.hasMore,
                    "client B examined its ghost branch to the tail");
            assertNotNull(aOut.get());
            assertEquals(TriState.FALSE, aOut.get().hasMore,
                    "client A must NOT be cancelled by B's request — it "
                    + "reached the tail on its own");
        } finally {
            release.countDown();
        }
    }

    @Test
    void shouldCancelWithinSameSessionWithoutFakingComplete() throws Exception {
        write(source.resolve("s1.txt"), "lorem");
        write(source.resolve("s2.txt"), "lorem");
        write(source.resolve("s3.txt"), "lorem");
        indexAndOpen();
        Files.delete(source.resolve("s1.txt"));
        Files.delete(source.resolve("s2.txt"));
        Files.delete(source.resolve("s3.txt"));

        CountDownLatch release = blockExistencePool();
        AtomicReference<SearchOutcome> aOut = new AtomicReference<>();
        try {
            Thread a = new Thread(() -> aOut.set(service.searchDetailed(req("client-A"))));
            a.start();
            Thread.sleep(150); // A is mid-refill when its successor arrives

            service.searchDetailed(req("client-A")); // supersedes A
            a.join(30_000);

            assertNotNull(aOut.get());
            assertEquals(TriState.UNKNOWN, aOut.get().hasMore,
                    "a genuinely cancelled request must not pretend the "
                    + "page is complete (FALSE)");
        } finally {
            release.countDown();
        }
    }

    @Test
    void shouldGiveNullTagRequestsPrivateSessions() throws Exception {
        write(source.resolve("n1.txt"), "lorem");
        write(source.resolve("n2.txt"), "lorem");
        indexAndOpen();
        Files.delete(source.resolve("n1.txt"));
        Files.delete(source.resolve("n2.txt"));

        CountDownLatch release = blockExistencePool();
        AtomicReference<SearchOutcome> aOut = new AtomicReference<>();
        try {
            Thread a = new Thread(() -> aOut.set(
                    service.searchDetailed(new SearchRequest("lorem", 50, null))));
            a.start();
            Thread.sleep(150);

            service.searchDetailed(new SearchRequest("lorem", 50, null));
            a.join(30_000);

            assertNotNull(aOut.get());
            assertEquals(TriState.FALSE, aOut.get().hasMore,
                    "a null-tag request has a private session and is never "
                    + "superseded — it reached the tail");
        } finally {
            release.countDown();
        }
    }
}
