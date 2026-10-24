package org.abitware.docfinder.web;

import com.sun.net.httpserver.HttpServer;
import org.abitware.docfinder.index.IndexSettings;
import org.abitware.docfinder.index.LuceneIndexer;
import org.abitware.docfinder.search.LuceneSearchService;
import org.abitware.docfinder.search.SearchService;
import org.abitware.docfinder.search.SourceRootProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A7/9.1 end-to-end: real LuceneIndexer -> LuceneSearchService -> HTTP
 * SearchHandler chain over a real HttpServer. Covers content+scope queries,
 * live source changes, upsert visibility, migration-style fixtures (stale
 * docs, offline/unreachable source, partial commit) and the
 * not-ready -> recover -> rebuild-unavailable -> recover service lifecycle.
 */
@Execution(ExecutionMode.SAME_THREAD)
class DirectoryScopeEndToEndTest {

    @TempDir
    Path fixtureRoot;

    private Path sourceA;
    private Path sourceB;
    private Path index;
    private IndexSettings settings;
    private HttpServer server;
    private String base;

    private final AtomicReference<SearchService> serviceRef = new AtomicReference<>();
    private final List<Path> providerRoots = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        sourceA = fixtureRoot.resolve("SrcA");
        sourceB = fixtureRoot.resolve("SrcB");
        index = fixtureRoot.resolve("index");
        Files.createDirectories(sourceA.resolve("sub"));
        Files.createDirectories(sourceB);
        write(sourceA.resolve("alpha-a.txt"), "alpha content a");
        write(sourceA.resolve("sub").resolve("alpha-deep.txt"), "alpha deep");
        write(sourceB.resolve("alpha-b.txt"), "alpha content b");

        settings = new IndexSettings();
        try (LuceneIndexer indexer = new LuceneIndexer(index, settings)) {
            indexer.indexFolders(Arrays.asList(sourceA, sourceB), false);
        }

        providerRoots.add(sourceA);
        SourceRootProvider provider = () -> new ArrayList<>(providerRoots);
        serviceRef.set(new LuceneSearchService(index, settings, provider));

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/search", new SearchHandler(serviceRef::get));
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
        SearchService s = serviceRef.getAndSet(null);
        if (s != null) {
            s.close();
        }
    }

    private static void write(Path p, String content) throws IOException {
        Files.write(p, content.getBytes(StandardCharsets.UTF_8));
    }

    private static String enc(String v) {
        try {
            return URLEncoder.encode(v, "UTF-8");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static final class Resp {
        final int status;
        final String body;
        Resp(int status, String body) { this.status = status; this.body = body; }
    }

    private Resp get(String query) throws IOException {
        URL url = new URL(base + "/api/search?" + query);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(5000);
        int status = c.getResponseCode();
        InputStream in = status < 400 ? c.getInputStream() : c.getErrorStream();
        String body;
        try (InputStream s = in) {
            if (s == null) return new Resp(status, "");
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = s.read(buf)) >= 0) bos.write(buf, 0, n);
            body = new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
        c.disconnect();
        return new Resp(status, body);
    }

    // ── 9.1: real indexer -> service -> HTTP, content + scope ───────────────

    @Test
    void shouldSearchWithinDirectoryOverRealHttpChain() throws Exception {
        Resp scoped = get("q=alpha&directory=" + enc(sourceA.resolve("sub").toString()));
        assertEquals(200, scoped.status, scoped.body);
        assertTrue(scoped.body.contains("alpha-deep.txt"), scoped.body);
        assertFalse(scoped.body.contains("alpha-a.txt"), "sibling must be filtered out: " + scoped.body);
        assertFalse(scoped.body.contains("alpha-b.txt"), "other source must be filtered out");
        assertTrue(scoped.body.contains("\"parentDirectory\":"), scoped.body);

        Resp global = get("q=alpha");
        assertEquals(200, global.status, global.body);
        assertTrue(global.body.contains("alpha-a.txt") && global.body.contains("alpha-b.txt"),
                "unscoped search must still see both sources");
    }

    // ── 9.1: live source change reflected per request ───────────────────────

    @Test
    void shouldReflectSourceListChangesWithoutRestart() throws Exception {
        String dirInB = sourceB.toString();
        Resp before = get("q=alpha&directory=" + enc(dirInB));
        assertEquals(422, before.status, "sourceB not yet configured: " + before.body);
        assertTrue(before.body.contains("DIRECTORY_NOT_INDEXED"), before.body);

        providerRoots.add(sourceB); // "add a source" — provider sees it next request
        Resp after = get("q=alpha&directory=" + enc(dirInB));
        assertEquals(200, after.status, after.body);
        assertTrue(after.body.contains("alpha-b.txt"), after.body);
    }

    // ── 9.1: upsert makes new content searchable ────────────────────────────

    @Test
    void shouldExposeUpsertedFileAfterCommit() throws Exception {
        Path newFile = sourceA.resolve("sub").resolve("gamma-new.txt");
        write(newFile, "gamma brand new content");

        Resp before = get("q=gamma&directory=" + enc(sourceA.toString()));
        assertFalse(before.body.contains("gamma-new.txt"));

        try (LuceneIndexer indexer = new LuceneIndexer(index, settings)) {
            indexer.upsertFile(newFile);
            indexer.commit();
        }

        Resp after = get("q=gamma&directory=" + enc(sourceA.resolve("sub").toString()));
        assertEquals(200, after.status, after.body);
        assertTrue(after.body.contains("gamma-new.txt"), after.body);
        assertTrue(after.body.contains("\"path_scope\"") == false, "path_scope must not leak into JSON");
    }

    // ── 9.1: stale doc for deleted file still resolves under scope ──────────

    @Test
    void shouldKeepStaleDocSearchableUntilReindex() throws Exception {
        Files.delete(sourceA.resolve("alpha-a.txt")); // disk gone, index doc remains
        Resp r = get("q=alpha&directory=" + enc(sourceA.toString()));
        assertEquals(200, r.status, r.body);
        assertTrue(r.body.contains("alpha-a.txt"),
                "stale index entry remains searchable until Index All/rebuild");
    }

    // ── 9.1: offline/unreachable configured source degrades gracefully ──────

    @Test
    void shouldHandleUnreachableSourceRootWithoutFailingOthers() throws Exception {
        Path ghost = fixtureRoot.resolve("GhostSource"); // configured but never existed
        providerRoots.add(ghost);
        Resp r = get("q=alpha&directory=" + enc(ghost.toString()));
        assertEquals(200, r.status, "scope under offline source must not error: " + r.body);
        assertTrue(r.body.contains("\"count\":0"), r.body);

        // and global search still serves remaining sources
        Resp g = get("q=alpha");
        assertEquals(200, g.status);
        assertTrue(g.body.contains("alpha-a.txt"), g.body);
    }

    // ── 9.1: partial commit — committed docs survive a failed later write ───

    /**
     * Deny write on the index directory so the NEXT commit fails with a real
     * filesystem error (QA24-06). Returns the original ACL to restore later.
     */
    private static java.util.List<java.nio.file.attribute.AclEntry> denyWriteOnDir(Path dir)
            throws IOException {
        java.nio.file.attribute.AclFileAttributeView view =
                Files.getFileAttributeView(dir, java.nio.file.attribute.AclFileAttributeView.class);
        if (view == null) {
            return null;
        }
        java.nio.file.attribute.UserPrincipal me = java.nio.file.FileSystems.getDefault()
                .getUserPrincipalLookupService()
                .lookupPrincipalByName(System.getProperty("user.name"));
        java.util.List<java.nio.file.attribute.AclEntry> original =
                new java.util.ArrayList<>(view.getAcl());
        java.util.List<java.nio.file.attribute.AclEntry> denied =
                new java.util.ArrayList<>(original);
        denied.add(0, java.nio.file.attribute.AclEntry.newBuilder()
                .setType(java.nio.file.attribute.AclEntryType.DENY)
                .setPrincipal(me)
                .setPermissions(java.nio.file.attribute.AclEntryPermission.WRITE_DATA,
                        java.nio.file.attribute.AclEntryPermission.ADD_FILE,
                        java.nio.file.attribute.AclEntryPermission.ADD_SUBDIRECTORY,
                        java.nio.file.attribute.AclEntryPermission.WRITE_ATTRIBUTES,
                        java.nio.file.attribute.AclEntryPermission.DELETE)
                .build());
        view.setAcl(denied);
        return original;
    }

    private static void restoreAcl(Path dir,
            java.util.List<java.nio.file.attribute.AclEntry> original) {
        try {
            Files.getFileAttributeView(dir, java.nio.file.attribute.AclFileAttributeView.class)
                    .setAcl(original);
        } catch (IOException ignore) {
        }
    }

    @Test
    void shouldServeCommittedDataAfterPartialCommitFailure() throws Exception {
        // QA24-06: source A commits; the index dir then becomes unwritable, so
        // source B's commit REALLY fails with an IOException — committed data
        // must remain searchable after reopen, uncommitted docs must not leak.
        Path extra = sourceA.resolve("delta.txt");
        write(extra, "delta partial commit");
        write(sourceB.resolve("delta-b.txt"), "delta partial b");
        LuceneIndexer indexer = new LuceneIndexer(index, settings);
        try {
            indexer.indexFolder(sourceA); // commit #1 succeeds
            java.util.List<java.nio.file.attribute.AclEntry> original = denyWriteOnDir(index);
            org.junit.jupiter.api.Assumptions.assumeTrue(original != null,
                    "ACL attribute view unsupported on this filesystem");
            try {
                // The ACL denial fails the commit's segment write; Lucene
                // surfaces that as AlreadyClosedException (writer shuts down
                // on the write tragedy) — a real propagated failure either
                // way, never silently swallowed.
                org.apache.lucene.store.AlreadyClosedException boom = assertThrows(
                        org.apache.lucene.store.AlreadyClosedException.class,
                        () -> indexer.indexFolder(sourceB),
                        "the second commit must really fail, not be swallowed");
                assertNotNull(boom);
            } finally {
                restoreAcl(index, original);
            }
        } finally {
            try {
                indexer.close(); // rolls back the uncommitted second source
            } catch (Exception ignore) {
            }
        }
        // service reopened as the app would after recovery
        serviceRef.get().close();
        serviceRef.set(new LuceneSearchService(index, settings, () -> Collections.singletonList(sourceA)));

        Resp r = get("q=delta&directory=" + enc(sourceA.toString()));
        assertEquals(200, r.status, r.body);
        assertTrue(r.body.contains("delta.txt"), "committed doc must survive later failed write");
        assertFalse(r.body.contains("delta-b.txt"), "uncommitted docs must not appear");
    }

    // ── 9.1: not-ready -> recover -> rebuild-unavailable -> recover ─────────

    @Test
    void shouldCycleThroughServiceAvailabilityStates() throws Exception {
        SearchService live = serviceRef.get();

        serviceRef.set(null); // not ready / rebuilding
        Resp down = get("q=alpha");
        assertEquals(503, down.status, down.body);
        assertTrue(down.body.contains("INDEX_UNAVAILABLE"), down.body);
        // empty query still succeeds during rebuild
        Resp empty = get("q=+&directory=" + enc(sourceA.toString()));
        assertEquals(200, empty.status);
        assertEquals("{\"results\":[],\"count\":0}", empty.body);

        serviceRef.set(live); // recovered (same service still valid)
        Resp back = get("q=alpha&directory=" + enc(sourceA.toString()));
        assertEquals(200, back.status, back.body);
        assertTrue(back.body.contains("alpha-a.txt"), back.body);
    }

    // ── 9.1: provider failure -> SOURCE_CONFIG_UNAVAILABLE 503 ──────────────

    @Test
    void shouldMapProviderFailureTo503() throws Exception {
        SearchService s = new LuceneSearchService(index, settings, () -> {
            throw new IOException("sources.txt unreadable");
        });
        serviceRef.get().close();
        serviceRef.set(s);
        Resp r = get("q=alpha&directory=" + enc(sourceA.toString()));
        assertEquals(503, r.status, r.body);
        assertTrue(r.body.contains("SOURCE_CONFIG_UNAVAILABLE"), r.body);
        assertFalse(r.body.contains("unreadable"), "internal detail must not leak");
    }
}
