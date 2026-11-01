package org.abitware.docfinder.index;

import org.abitware.docfinder.index.LuceneIndexer.BatchObserver;
import org.abitware.docfinder.index.LuceneIndexer.BatchOptions;
import org.abitware.docfinder.index.LuceneIndexer.Scheduling;
import org.abitware.docfinder.util.IsolatedFileAccess;
import org.abitware.docfinder.util.Utils;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class LuceneIndexerConcurrencyTest {
    @TempDir Path temporary;
    private String originalDirectory;
    private Path corpus;

    @BeforeEach
    void isolate() {
        originalDirectory = System.getProperty("user.dir");
        System.setProperty("user.dir", temporary.toString());
        LuceneIndexer.resetExtractionStateForTests();
        try {
            java.net.URL url = LuceneIndexerConcurrencyTest.class
                    .getResource("/index/virtual-thread-corpus/manifest.tsv");
            corpus = url == null ? null : Paths.get(url.toURI()).getParent();
        } catch (java.net.URISyntaxException e) {
            throw new AssertionError(e);
        }
        assertTrue(corpus != null && Files.exists(corpus.resolve("manifest.tsv")),
                "fixture corpus missing");
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

    private static final class ManifestEntry {
        String relative;
        String kind;
        boolean doc;
        String token;
    }

    private List<ManifestEntry> manifest() throws IOException {
        List<ManifestEntry> entries = new ArrayList<>();
        for (String line : Files.readAllLines(corpus.resolve("manifest.tsv"))) {
            if (line.startsWith("#") || line.isBlank()) continue;
            String[] cols = line.split("\t", -1);
            ManifestEntry e = new ManifestEntry();
            e.relative = cols[0];
            e.kind = cols[1];
            e.doc = "1".equals(cols[2]);
            e.token = cols.length > 3 && !cols[3].isEmpty() ? cols[3] : null;
            entries.add(e);
        }
        return entries;
    }

    /** 语料复制到源目录，并在测试侧补建无法入 git 的项（空目录、~$、.tmp、node_modules）。 */
    private Path materialize(String name, long fixedMtime) throws IOException {
        Path target = temporary.resolve(name);
        Files.createDirectories(target);
        try (var stream = Files.walk(corpus)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                Path rel = corpus.relativize(p);
                if (rel.toString().equals("manifest.tsv")) continue;
                Path dest = target.resolve(rel.toString());
                if (Files.isDirectory(p)) Files.createDirectories(dest);
                else Files.copy(p, dest, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        // git 无法保存的条目由测试补建。
        Files.createDirectories(target.resolve("emptydir"));
        Files.write(target.resolve("~$lock.docx"), new byte[] { 1 });
        Files.write(target.resolve("draft.tmp"), new byte[] { 1 });
        Files.createDirectories(target.resolve("node_modules/pkg"));
        Files.write(target.resolve("node_modules/pkg/index.js"), "x".getBytes());
        // 固定 mtime，避免时序差异。
        try (var stream = Files.walk(target)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                Files.setLastModifiedTime(p, java.nio.file.attribute.FileTime.fromMillis(fixedMtime));
            }
        }
        return target;
    }

    /** 预热全部 helper：4 并发提取 + ≥2 并发元数据，排除冷启动误报。 */
    private void warmUpHelpers(Path sampleFile) throws Exception {
        ExecutorService warm = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Boolean>> tasks = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                tasks.add(() -> IsolatedFileAccess.extract(sampleFile, 1000, 30000).isSuccessful());
            }
            for (int i = 0; i < 2; i++) {
                tasks.add(() -> IsolatedFileAccess.isTextLike(sampleFile, 10000));
                tasks.add(() -> IsolatedFileAccess.attributes(sampleFile, 10000) != null);
            }
            for (Future<Boolean> f : warm.invokeAll(tasks)) f.get();
        } finally {
            warm.shutdownNow();
        }
    }

    private IndexSettings settings() {
        IndexSettings s = new IndexSettings();
        s.parseTimeoutSec = 30;
        return s;
    }

    private BatchOptions options(Scheduling scheduling, BatchObserver observer) {
        BatchOptions o = new BatchOptions();
        o.scheduling = scheduling;
        if (observer != null) o.observer = observer;
        return o;
    }

    private Map<String, String> docKindByPath(Path indexDir) throws IOException {
        Map<String, String> result = new HashMap<>();
        try (FSDirectory dir = FSDirectory.open(indexDir);
             DirectoryReader reader = DirectoryReader.open(dir)) {
            // updateDocument 是删除+新增：maxDoc 仍能看到未合并的已删除
            // 文档，必须跳过 liveDocs 之外的位置。
            var live = org.apache.lucene.index.MultiBits.getLiveDocs(reader);
            for (int i = 0; i < reader.maxDoc(); i++) {
                if (live != null && !live.get(i)) continue;
                org.apache.lucene.document.Document d = reader.storedFields().document(i);
                assertNull(result.put(d.get("path"), d.get("kind")), "duplicate live path: " + d.get("path"));
            }
            return result;
        }
    }

    private int numDocs(Path indexDir) throws IOException {
        try (FSDirectory dir = FSDirectory.open(indexDir);
             DirectoryReader reader = DirectoryReader.open(dir)) {
            return reader.numDocs();
        }
    }

    private Analyzer analyzerFor(String field) {
        if ("content_zh".equals(field)) return Analyzers.chinese();
        if ("content_ja".equals(field)) return Analyzers.japanese();
        return new StandardAnalyzer();
    }

    private Set<String> termQueryPaths(Path indexDir, String field, String term) throws IOException {
        Set<String> paths = new HashSet<>();
        try (FSDirectory dir = FSDirectory.open(indexDir);
             DirectoryReader reader = DirectoryReader.open(dir)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            for (ScoreDoc hit : searcher.search(
                    new org.apache.lucene.search.TermQuery(
                            new org.apache.lucene.index.Term(field, term)), 1000).scoreDocs) {
                paths.add(reader.storedFields().document(hit.doc).get("path"));
            }
        }
        return paths;
    }

    private Set<String> queryPaths(Path indexDir, String field, String text) throws Exception {
        Set<String> paths = new HashSet<>();
        try (Analyzer analyzer = analyzerFor(field);
             FSDirectory dir = FSDirectory.open(indexDir);
             DirectoryReader reader = DirectoryReader.open(dir)) {
            Query q = new QueryParser(field, analyzer).parse(text);
            IndexSearcher searcher = new IndexSearcher(reader);
            var hits = searcher.search(q, 1000);
            assertEquals(hits.scoreDocs.length, hits.totalHits.value(), "complete totalHits oracle for " + q);
            var stored = reader.storedFields();
            for (ScoreDoc hit : hits.scoreDocs) {
                assertTrue(paths.add(stored.document(hit.doc).get("path")), "duplicate query path for " + q);
            }
        }
        return paths;
    }

    private void resetOracleState() {
        Tombstones.resetForRebuild();
        Tombstones.close();
        LuceneIndexer.resetExtractionStateForTests();
    }

    /** manifest 以 "." 标记 source 根目录；索引存根目录本身的路径（无 "/." 后缀），oracle 必须同样归一化。 */
    private static String expectedPath(Path root, String relative) {
        return Utils.normalizeForIndex(root.resolve(relative).normalize());
    }

    private void assertManifestAndQueries(Path serial, Path parallel, List<Path> roots) throws Exception {
        List<ManifestEntry> entries = manifest();
        Map<String, String> expected = new HashMap<>();
        for (Path root : roots) {
            for (ManifestEntry entry : entries) {
                if (entry.doc) expected.put(expectedPath(root, entry.relative), entry.kind);
            }
        }
        assertEquals(expected, docKindByPath(serial), "complete serial path/kind manifest");
        assertEquals(expected, docKindByPath(parallel), "complete parallel path/kind manifest");
        assertEquals(expected.size(), numDocs(serial));
        assertEquals(expected.size(), numDocs(parallel));
        for (ManifestEntry entry : entries) {
            if (entry.token == null) continue;
            Set<String> wanted = new HashSet<>();
            for (Path root : roots) wanted.add(expectedPath(root, entry.relative));
            assertEquals(wanted, queryPaths(serial, "content", entry.token), "serial token " + entry.token);
            assertEquals(wanted, queryPaths(parallel, "content", entry.token), "parallel token " + entry.token);
        }
        for (String[] query : new String[][] {
                {"name", "english.txt"}, {"ext", "pdf"}, {"kind", "folder"},
                {"content", "nonexistenttokenxyz"}, {"content_zh", "山水"}, {"content_ja", "日本語"}}) {
            Set<String> hits = queryPaths(serial, query[0], query[1]);
            assertEquals(hits, queryPaths(parallel, query[0], query[1]), Arrays.toString(query));
            if (query[1].startsWith("nonexistent")) assertTrue(hits.isEmpty());
            else assertFalse(hits.isEmpty(), "nonvacuous query: " + Arrays.toString(query));
        }
        for (String[] cjk : new String[][] {{"content_zh", "山水", "chinese.txt"},
                {"content_ja", "日本語", "japanese.txt"}}) {
            Set<String> wanted = new HashSet<>();
            for (Path root : roots) wanted.add(Utils.normalizeForIndex(root.resolve("docs").resolve(cjk[2])));
            assertEquals(wanted, queryPaths(serial, cjk[0], cjk[1]));
            assertEquals(wanted, queryPaths(parallel, cjk[0], cjk[1]));
        }
        for (Path root : roots) {
            String scope = Utils.normalizeForIndex(root.resolve("docs"));
            Set<String> wanted = java.util.Collections.singleton(scope);
            assertEquals(wanted, termQueryPaths(serial, "path_scope", scope));
            assertEquals(wanted, termQueryPaths(parallel, "path_scope", scope));
        }
    }

    @Test
    void shouldMatchSerialIndexForFixtureCorpus() throws Exception {
        Path source1 = materialize("source1", 1_700_000_000_000L);
        Path source2 = materialize("source2", 1_700_000_000_000L);
        warmUpHelpers(source1.resolve("docs/english.txt"));
        Path serial = temporary.resolve("serial-index");
        Path parallel = temporary.resolve("parallel-index");
        try (LuceneIndexer indexer = new LuceneIndexer(serial, settings(), options(Scheduling.SERIAL, null))) {
            indexer.indexFolder(source1);
        }
        resetOracleState();
        try (LuceneIndexer indexer = new LuceneIndexer(parallel, settings(), options(Scheduling.PARALLEL, null))) {
            indexer.indexFolder(source1);
        }
        assertManifestAndQueries(serial, parallel, java.util.Collections.singletonList(source1));

        List<Path> both = Arrays.asList(source1, source2);
        Path serial2 = temporary.resolve("serial-idx2");
        Path parallel2 = temporary.resolve("parallel-idx2");
        resetOracleState();
        try (LuceneIndexer indexer = new LuceneIndexer(serial2, settings(), options(Scheduling.SERIAL, null))) {
            indexer.indexFolders(both, false);
        }
        resetOracleState();
        try (LuceneIndexer indexer = new LuceneIndexer(parallel2, settings(), options(Scheduling.PARALLEL, null))) {
            indexer.indexFolders(both, false);
        }
        assertManifestAndQueries(serial2, parallel2, both);

        Path stale = Files.createDirectories(temporary.resolve("stale-source"));
        Files.writeString(stale.resolve("stalefile.txt"), "staleness leftover doc");
        resetOracleState();
        try (LuceneIndexer indexer = new LuceneIndexer(serial2, settings(), options(Scheduling.SERIAL, null))) {
            indexer.indexFolder(stale);
            assertFalse(queryPaths(serial2, "content", "staleness").isEmpty());
            indexer.indexFolders(both, true);
        }
        resetOracleState();
        try (LuceneIndexer indexer = new LuceneIndexer(parallel2, settings(), options(Scheduling.PARALLEL, null))) {
            indexer.indexFolder(stale);
            assertFalse(queryPaths(parallel2, "content", "staleness").isEmpty());
            indexer.indexFolders(both, true);
        }
        assertManifestAndQueries(serial2, parallel2, both);
        assertTrue(queryPaths(serial2, "content", "staleness").isEmpty());
        assertTrue(queryPaths(parallel2, "content", "staleness").isEmpty());
        resetOracleState();
        try (LuceneIndexer indexer = new LuceneIndexer(parallel2, settings(), options(Scheduling.PARALLEL, null))) {
            indexer.indexFolders(both, false);
        }
        assertManifestAndQueries(serial2, parallel2, both);
    }

    @Test
    void shouldSerializeOverlappingSources() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("overlap-root"));
        Path sub = Files.createDirectories(root.resolve("docs"));
        Path target = sub.resolve("changing.txt");
        Files.writeString(target, "oldbodytoken");
        CountDownLatch firstWriteBlocked = new CountDownLatch(1);
        CountDownLatch releaseFirstWrite = new CountDownLatch(1);
        CountDownLatch secondSubmitted = new CountDownLatch(1);
        AtomicInteger sources = new AtomicInteger();
        AtomicInteger updates = new AtomicInteger();
        BatchOptions options = options(Scheduling.PARALLEL, new BatchObserver() {
            @Override public void onSourceSubmitted(LuceneIndexer.BatchSource source) {
                if (sources.incrementAndGet() == 2) secondSubmitted.countDown();
            }
            @Override public void beforeUpdate(Path path) {
                if (path.equals(target) && updates.incrementAndGet() == 1) {
                    firstWriteBlocked.countDown();
                    LuceneIndexerSessionFailureTest.awaitIgnoringInterrupts(releaseFirstWrite);
                }
            }
        });
        options.extractor = (path, maxChars, timeoutMs) ->
                IsolatedFileAccess.Extraction.of(Files.readString(path), "text/plain", false, true);
        Path index = temporary.resolve("overlap-index");
        ExecutorService owner = Executors.newSingleThreadExecutor();
        Future<Integer> result = null;
        try (LuceneIndexer indexer = new LuceneIndexer(index, settings(), options)) {
            try {
                result = owner.submit(() -> indexer.indexFolders(Arrays.asList(root, sub, root), false));
                assertTrue(firstWriteBlocked.await(15, TimeUnit.SECONDS));
                assertFalse(secondSubmitted.await(200, TimeUnit.MILLISECONDS),
                        "overlapping source started while the earlier write remained blocked");
                assertEquals(1, sources.get());
                Files.writeString(target, "newbodytoken");
                releaseFirstWrite.countDown();
                result.get(30, TimeUnit.SECONDS);
                assertEquals(3, sources.get());
                assertEquals(3, updates.get());
                assertEquals(3, docKindByPath(index).size());
                assertTrue(queryPaths(index, "content", "oldbodytoken").isEmpty());
                assertEquals(java.util.Collections.singleton(Utils.normalizeForIndex(target)),
                        queryPaths(index, "content", "newbodytoken"));
            } finally {
                releaseFirstWrite.countDown();
                if (result != null) result.get(30, TimeUnit.SECONDS);
            }
        } finally {
            owner.shutdownNow();
            assertTrue(owner.awaitTermination(15, TimeUnit.SECONDS));
        }
    }

    @Test
    void shouldKeepNameOnlyDocumentsWhenExtractionFails() throws Exception {
        Path source = temporary.resolve("fail-source");
        Files.createDirectories(source);
        Path bad = source.resolve("broken.txt");
        Path slow = source.resolve("slow.txt");
        Path good = source.resolve("good.txt");
        Files.write(bad, "irrelevant".getBytes());
        Files.write(slow, "irrelevant".getBytes());
        Files.write(good, "irrelevant".getBytes());

        ConcurrentLinkedQueue<String> outcomes = new ConcurrentLinkedQueue<>();
        outcomes.add("fail");
        outcomes.add("timeout");
        BatchOptions o = options(Scheduling.SERIAL, null);
        o.extractor = (path, maxChars, timeoutMs) -> {
            String next = path.equals(bad) ? "fail" : path.equals(slow) ? "timeout" : "ok";
            if ("fail".equals(next)) throw new IOException("injected parse failure");
            if ("timeout".equals(next)) throw new IOException("Source I/O deadline exceeded", new TimeoutException());
            return IsolatedFileAccess.Extraction.of("zzgoodtoken", "text/plain", false, true);
        };
        Path index = temporary.resolve("fail-index");
        try (LuceneIndexer indexer = new LuceneIndexer(index, settings(), o)) {
            indexer.indexFolder(source);
        }
        // 名称可检索、正文为空、后续文件正常。
        // StandardAnalyzer 把 "broken.txt" 视为单一 token（点是 mid-letter），
        // 因此 name 查询必须带完整文件名。
        assertFalse(queryPaths(index, "name", "broken.txt").isEmpty());
        assertFalse(queryPaths(index, "name", "slow.txt").isEmpty());
        assertEquals(1, queryPaths(index, "content", "zzgoodtoken").size());
        // 正文查询用互不相交的 token，避免 OR 匹配到共享词元。
        assertTrue(queryPaths(index, "content", "zzbrokentoken").isEmpty());
        // 单次 timeout（<12）不打开熔断器：FAILED_CONTENT_CACHE 语义保留，
        // good.txt 仍被提取。
        assertEquals(1, queryPaths(index, "name", "good.txt").size());
    }

    /* ------------------------- §7.3 熔断统计 ------------------------- */

    private static IOException timeout() {
        return new IOException("Source I/O deadline exceeded", new TimeoutException());
    }

    private Path txtSource(String name, int files) throws IOException {
        Path source = temporary.resolve(name);
        Files.createDirectories(source);
        for (int i = 0; i < files; i++) {
            Files.write(source.resolve(String.format("f%02d.txt", i)), ("body-" + i).getBytes());
        }
        return source;
    }

    @Test
    void shouldOpenBreakerAfterTwelveConsecutiveTimeoutCompletions() throws Exception {
        // SERIAL：完成顺序即遍历顺序，12 次连续 timeout 后熔断打开，
        // 后续文件跳过正文仍是 name-only 文档。
        Path source = txtSource("breaker-src", 15);
        AtomicInteger calls = new AtomicInteger();
        BatchOptions o = options(Scheduling.SERIAL, null);
        o.extractor = (path, maxChars, timeoutMs) -> {
            calls.incrementAndGet();
            throw timeout();
        };
        try (LuceneIndexer indexer = new LuceneIndexer(temporary.resolve("breaker-idx"), settings(), o)) {
            indexer.indexFolder(source);
        }
        assertEquals(12, calls.get(), "breaker must open after exactly 12 consecutive timeouts");
        assertEquals(16, numDocs(temporary.resolve("breaker-idx")),
                "files keep name-only documents while the breaker is open");
        assertFalse(queryPaths(temporary.resolve("breaker-idx"), "name", "f14").isEmpty());
    }

    private static final class CompletionGates {
        final AtomicInteger calls = new AtomicInteger();
        final java.util.concurrent.ConcurrentMap<Path, Integer> ordinals = new java.util.concurrent.ConcurrentHashMap<>();
        final CountDownLatch[] entered = new CountDownLatch[64];
        final CountDownLatch[] finished = new CountDownLatch[64];
        final java.util.concurrent.Semaphore[] releases = new java.util.concurrent.Semaphore[64];
        CompletionGates() {
            for (int i = 0; i < 64; i++) {
                entered[i] = new CountDownLatch(1);
                finished[i] = new CountDownLatch(1);
                releases[i] = new java.util.concurrent.Semaphore(0);
            }
        }
        void releaseAndAwait(int ordinal) throws InterruptedException {
            assertTrue(entered[ordinal].await(15, TimeUnit.SECONDS), "extractor admission " + ordinal);
            releases[ordinal].release();
            assertTrue(finished[ordinal].await(15, TimeUnit.SECONDS), "extraction completion " + ordinal);
        }
        void releaseAll() {
            for (java.util.concurrent.Semaphore release : releases) release.release(100);
        }
        void finished(Path path) {
            Integer ordinal = ordinals.get(path);
            if (ordinal != null) finished[ordinal].countDown();
        }
        int enter(Path path) throws IOException {
            int ordinal = calls.getAndIncrement();
            ordinals.put(path, ordinal);
            entered[ordinal].countDown();
            try {
                releases[ordinal].acquire();
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IOException("controlled extractor interrupted", failure);
            }
            return ordinal;
        }
    }

    @Test
    void shouldOpenBreakerAfterTwelveConsecutiveTimeoutCompletionsParallel() throws Exception {
        runStickyParallelBreaker(false);
    }

    @Test
    void shouldResetTimeoutStreakOnSuccessfulCompletion() throws Exception {
        Path source = txtSource("reset-src", 23);
        CompletionGates gates = new CompletionGates();
        BatchOptions options = options(Scheduling.PARALLEL, new BatchObserver() {
            @Override public void onTaskFinished(Path path) { gates.finished(path); }
        });
        options.extractor = (path, maxChars, timeoutMs) -> {
            int ordinal = gates.enter(path);
            if (ordinal != 11) throw timeout();
            return IsolatedFileAccess.Extraction.of("recoveredtoken", "text/plain", false, true);
        };
        ExecutorService owner = Executors.newSingleThreadExecutor();
        Future<Integer> result = null;
        try (LuceneIndexer indexer = new LuceneIndexer(temporary.resolve("reset-idx"), settings(), options)) {
            try {
                result = owner.submit(() -> indexer.indexFolder(source));
                // 完成顺序固定为 11 timeout → success → 11 timeout；不重置 streak 会提前跳过正文。
                for (int i = 0; i < 23; i++) gates.releaseAndAwait(i);
                result.get(30, TimeUnit.SECONDS);
                assertEquals(23, gates.calls.get());
            } finally {
                gates.releaseAll();
                if (result != null) result.get(30, TimeUnit.SECONDS);
            }
        } finally {
            owner.shutdownNow();
            assertTrue(owner.awaitTermination(15, TimeUnit.SECONDS));
        }
    }

    @Test
    void shouldKeepBreakerOpenUntilNextBatch() throws Exception {
        runStickyParallelBreaker(true);
    }

    private void runStickyParallelBreaker(boolean checkNextBatch) throws Exception {
        Path source = txtSource("sticky-src", 30);
        CompletionGates gates = new CompletionGates();
        CountDownLatch laterDispatch = new CountDownLatch(1);
        CountDownLatch releaseLaterDispatch = new CountDownLatch(1);
        AtomicInteger dispatchedFiles = new AtomicInteger();
        BatchOptions options = options(Scheduling.PARALLEL, new BatchObserver() {
            @Override public void onDispatchAttempt(Path path, boolean directory) {
                if (!directory && dispatchedFiles.incrementAndGet() == 15) {
                    laterDispatch.countDown();
                    LuceneIndexerSessionFailureTest.awaitIgnoringInterrupts(releaseLaterDispatch);
                }
            }
            @Override public void onTaskFinished(Path path) { gates.finished(path); }
        });
        options.extractor = (path, maxChars, timeoutMs) -> {
            int ordinal = gates.enter(path);
            if (ordinal != 0) throw timeout();
            return IsolatedFileAccess.Extraction.of("lateacceptedtoken", "text/plain", false, true);
        };
        ExecutorService owner = Executors.newSingleThreadExecutor();
        Future<Integer> result = null;
        Path index = temporary.resolve("sticky-idx");
        try (LuceneIndexer indexer = new LuceneIndexer(index, settings(), options)) {
            try {
                result = owner.submit(() -> indexer.indexFolder(source));
                assertTrue(gates.entered[0].await(15, TimeUnit.SECONDS));
                for (int i = 1; i <= 12; i++) gates.releaseAndAwait(i);
                assertTrue(laterDispatch.await(15, TimeUnit.SECONDS));
                // 第 12 次 timeout 已完成后才放行先准入的成功请求；成功不能关闭已打开的 breaker。
                gates.releaseAndAwait(0);
                int admittedBeforeLaterFiles = gates.calls.get();
                assertTrue(admittedBeforeLaterFiles >= 13 && admittedBeforeLaterFiles <= 14);
                gates.releaseAll();
                releaseLaterDispatch.countDown();
                result.get(30, TimeUnit.SECONDS);
                assertEquals(admittedBeforeLaterFiles, gates.calls.get(), "new files must skip content after breaker opens");
                assertEquals(31, numDocs(index));
                assertEquals(1, queryPaths(index, "content", "lateacceptedtoken").size());
                if (checkNextBatch) {
                    // 新路径避开失败缓存；禁止用 resetExtractionStateForTests 掩盖入口忘记重置。
                    Path nextSource = txtSource("next-batch-new-paths", 3);
                    AtomicInteger nextCalls = new AtomicInteger();
                    options.extractor = (path, maxChars, timeoutMs) -> {
                        nextCalls.incrementAndGet();
                        return IsolatedFileAccess.Extraction.of("nextbatchtoken", "text/plain", false, true);
                    };
                    indexer.indexFolder(nextSource);
                    assertEquals(3, nextCalls.get(), "indexFolder entry must reset the breaker");
                    assertEquals(3, queryPaths(index, "content", "nextbatchtoken").size());
                }
            } finally {
                gates.releaseAll();
                releaseLaterDispatch.countDown();
                if (result != null) result.get(30, TimeUnit.SECONDS);
            }
        } finally {
            owner.shutdownNow();
            assertTrue(owner.awaitTermination(15, TimeUnit.SECONDS));
        }
    }
}
