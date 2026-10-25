package org.abitware.docfinder.search;

import org.abitware.docfinder.index.IndexSettings;
import org.abitware.docfinder.index.LuceneIndexer;
import org.abitware.docfinder.index.Tombstones;
import org.abitware.docfinder.util.Utils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.*;

class RecoveryIntegrationTest {
    @TempDir Path temporary;

    @Test
    void shouldReturnSearchWhileRecoveryStorageIsBusy() throws Exception {
        Path source = Files.createDirectories(temporary.resolve("source"));
        Path file = Files.createDirectory(source.resolve("report"));
        Path index = temporary.resolve("index");
        IndexSettings settings = new IndexSettings();
        try (LuceneIndexer writer = new LuceneIndexer(index, settings)) { writer.indexFolder(source); }
        org.abitware.docfinder.util.IsolatedFileAccess.attributes(file, 10000);
        org.abitware.docfinder.util.IsolatedFileAccess.attributes(file, 10000);
        LuceneSearchService service = new LuceneSearchService(index, settings,
                () -> Collections.singletonList(source));
        java.util.concurrent.ExecutorService caller = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            Field registryLock = Tombstones.class.getDeclaredField("LOCK");
            registryLock.setAccessible(true);
            synchronized (registryLock.get(null)) {
                java.util.concurrent.Future<SearchOutcome> result = caller.submit(() -> service.searchDetailed(
                        new SearchRequest("report", 10, null, SearchScope.FOLDER, MatchMode.FUZZY)));
                assertEquals(1, result.get(2, java.util.concurrent.TimeUnit.SECONDS).results.size(),
                        "enqueueing optional recovery must never wait for its storage lock");
            }
        } finally { caller.shutdownNow(); service.close(); }
    }

    @Test
    void shouldReportUnknownWhenExhaustedCandidatesIncludeUnverifiedPaths() throws Exception {
        Class<?> stateType = Class.forName(LuceneSearchService.class.getName() + "$RefillState");
        java.lang.reflect.Constructor<?> constructor = stateType.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object state = constructor.newInstance();
        Field uncertain = stateType.getDeclaredField("hadUncertain");
        uncertain.setAccessible(true);
        uncertain.setBoolean(state, true);
        Method verdict = LuceneSearchService.class.getDeclaredMethod("resolveHasMore",
                int.class, int.class, boolean.class, stateType);
        verdict.setAccessible(true);
        assertEquals(TriState.UNKNOWN, verdict.invoke(null, 0, 50, true, state));
        assertEquals(TriState.TRUE, verdict.invoke(null, 51, 50, true, state));
    }

    @Test
    void shouldRestoreBeyondFirstRecoverySnapshotWithoutAnotherSearch() throws Exception {
        String original = System.getProperty("user.dir");
        Path app = Files.createDirectories(temporary.resolve("app"));
        Path source = Files.createDirectories(temporary.resolve("source"));
        Path index = temporary.resolve("index");
        LuceneSearchService service = null;
        try {
            System.setProperty("user.dir", app.toString());
            IndexSettings settings = new IndexSettings();
            try (LuceneIndexer indexer = new LuceneIndexer(index, settings)) {
                indexer.indexFolder(source);
            }
            String cursor = null;
            for (int i = 0; i < 4096; i++) {
                cursor = Utils.normalizeForIndex(source.resolve(String.format("absent-%04d.txt", i)));
                Tombstones.record(cursor);
            }
            Path restored = Files.createDirectory(source.resolve("zz-restored"));
            Tombstones.record(Utils.normalizeForIndex(restored));
            Tombstones.flush();
            service = new LuceneSearchService(index, settings, () -> Collections.singletonList(source));
            Field timer = LuceneSearchService.class.getDeclaredField("resurrectTimer");
            timer.setAccessible(true);
            ((ScheduledExecutorService) timer.get(service)).shutdownNow();
            Field last = LuceneSearchService.class.getDeclaredField("lastResurrectKey");
            last.setAccessible(true);
            last.set(service, cursor);
            Method drain = LuceneSearchService.class.getDeclaredMethod("drainResurrections");
            drain.setAccessible(true);
            // First attempt may only warm a cold metadata helper; no search is
            // used to drive recovery or to conceal a missed recovery cursor.
            for (int attempt = 0; attempt < 6 && Tombstones.contains(Utils.normalizeForIndex(restored)); attempt++) {
                last.set(service, cursor);
                drain.invoke(service);
            }
            assertFalse(Tombstones.contains(Utils.normalizeForIndex(restored)),
                    "successful recovery beyond entry 4096 must retire its evidence");
            try (org.apache.lucene.store.FSDirectory directory = org.apache.lucene.store.FSDirectory.open(index);
                 org.apache.lucene.index.DirectoryReader reader = org.apache.lucene.index.DirectoryReader.open(directory)) {
                assertEquals(1, new org.apache.lucene.search.IndexSearcher(reader).count(
                        new org.apache.lucene.search.TermQuery(new org.apache.lucene.index.Term("path",
                                Utils.normalizeForIndex(restored)))));
            }
        } finally {
            if (service != null) service.close();
            Tombstones.close();
            System.setProperty("user.dir", original);
        }
    }
}
