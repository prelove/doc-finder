package org.abitware.docfinder.ui;

import org.abitware.docfinder.index.ConfigManager;
import org.abitware.docfinder.index.LuceneIndexer;
import org.abitware.docfinder.index.SourceManager;
import org.abitware.docfinder.search.SearchException;
import org.abitware.docfinder.search.SearchErrorMessages;
import org.abitware.docfinder.search.SearchScope;
import org.abitware.docfinder.ui.components.DirectoryScopePanel;
import org.abitware.docfinder.web.WebServer;

import javax.swing.JComboBox;
import javax.swing.SwingUtilities;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Host-acceptance probe: drives a real MainWindow + real Lucene index +
 * optional real WebServer through the same entry points a user clicks
 * (menu callbacks, scope-panel commits, quick actions). Prints
 * "PASS|FAIL <scenario> <check> :: detail" lines for the evidence record.
 *
 * cwd MUST be the scenario's work dir (user.dir/.docfinder is the app base).
 * Usage: DevinAcceptanceProbe <scenario> <root> [webPort]
 * scenarios: desktop | m06empty | m06stale | m06rebuild | webdown | mkstale
 */
public final class DevinAcceptanceProbe {

    private static int passCount, failCount;
    private static MainWindow win;
    private static WebServer web;
    private static Path projA, nested, sibling, projB, special;

    public static void main(String[] args) throws Exception {
        String scenario = args[0];
        Path root = Paths.get(args[1]).toAbsolutePath().normalize();
        int port = args.length > 2 ? Integer.parseInt(args[2]) : 0;
        try {
            switch (scenario) {
                case "desktop":    scenarioDesktop(root, port); break;
                case "m06empty":   scenarioEmpty(root, port); break;
                case "m06stale":   scenarioStale(root, port); break;
                case "m06rebuild": scenarioRebuild(root, port); break;
                case "webdown":    scenarioWebDown(port); break;
                case "webjson":    scenarioWebJson(root, port); break;
                case "mkstale":    mkStaleIndex(Paths.get(args[2]), args[3]); break;
                default: throw new IllegalArgumentException("unknown scenario " + scenario);
            }
        } finally {
            if (win != null) SwingUtilities.invokeAndWait(() -> win.dispose());
            if (web != null) web.stop();
        }
        System.out.println("PROBE_DONE " + scenario + " pass=" + passCount + " fail=" + failCount);
        System.exit(failCount == 0 ? 0 : 1);
    }

    /* ================= scenarios ================= */

    private static void scenarioDesktop(Path root, int port) throws Exception {
        buildFixture(root);
        writeSources(projA, projB);
        Path idx = indexDir();
        indexAll(idx, false);
        startWindow(idx, port);

        // ---- M-01.1: combo select source + CONTENT/FILENAME-ish scopes ----
        edt(() -> win.getSearchField().setText("alpha"));
        selectSource(projA.toString());
        settleSearch();
        check("m01", "combo-A-allscope",
                hasRow("alpha-A.txt") && hasRow("alpha-nested.txt")
                        && !hasRow("alpha-sibling.txt") && !hasRow("alpha-B.txt"),
                rowsDump());

        setScope(SearchScope.CONTENT);
        edt(() -> invokeDoSearch(win));
        settleSearch();
        check("m01", "combo-A-contentscope",
                hasRow("alpha-A.txt") && hasRow("alpha-nested.txt") && !hasRow("alpha-B.txt"),
                rowsDump());

        // ---- M-01.2: FOLDER scope root hit, sibling excluded ----
        setScope(SearchScope.FOLDER);
        edt(() -> win.getSearchField().setText("ProjectA"));
        edt(() -> invokeDoSearch(win));
        settleSearch();
        check("m01", "folder-scope-root-hit",
                hasRow("ProjectA") && !hasRow("ProjectAB"), rowsDump());
        setScope(SearchScope.ALL);
        settleSearch(); // scope change auto-fires doSearch; let its done() land first
        edt(() -> win.getSearchField().setText("alpha"));

        // ---- M-01.4: typed-but-uncommitted path must not fire a search ----
        edt(() -> panel().getPathField().setText(projB.toString()));
        Thread.sleep(400);
        check("m01", "typing-no-commit",
                panel().getDirectoryPath().equals(projA.toString()),
                "committed=" + panel().getDirectoryPath());
        // Enter commits -> scoped to B now
        edt(() -> panel().getPathField().postActionEvent());
        settleSearch();
        check("m01", "enter-commit-B",
                hasRow("alpha-B.txt") && !hasRow("alpha-A.txt"),
                rowsDump() + " status=" + status() + " committed=" + panel().getDirectoryPath()
                        + " lastQuery=" + lastQuery());

        // ---- M-01.5: Windows-equivalent spelling shows configured source ----
        String equiv = projA.toString().replace('\\', '/').toLowerCase(java.util.Locale.ROOT);
        commitPath(equiv);
        String sel = comboSel();
        check("m01", "equivalent-path-source-not-custom",
                sel != null && !sel.equals("Custom") && !sel.equals("All sources")
                        && sel.equalsIgnoreCase(projA.toString()),
                "selected=" + sel);

        // ---- M-01.7: quick action — searchInDirectory on file parent ----
        edt(() -> win.searchInDirectory(nested.getParent().toString())); // ProjectA\sub
        settleSearch();
        check("m01", "quickaction-containing-folder",
                panel().getDirectoryPath().equals(nested.getParent().toString())
                        && hasRow("alpha-nested.txt") && !hasRow("alpha-A.txt"),
                "committed=" + panel().getDirectoryPath() + " " + rowsDump()
                        + " status=" + status());

        // ---- M-01.8: Clear returns to global ----
        edt(() -> panel().clearDirectory());
        settleSearch();
        check("m01", "clear-global",
                panel().getDirectoryPath().isEmpty()
                        && hasRow("alpha-A.txt") && hasRow("alpha-B.txt")
                        && !hasRow("alpha-sibling.txt"),
                "committed='" + panel().getDirectoryPath() + "' " + rowsDump()
                        + " status=" + status());

        // ---- M-01.9: bad sources row -> one warning; fix -> warning clears ----
        Path srcFile = sourcesFile();
        List<String> orig = Files.readAllLines(srcFile, StandardCharsets.UTF_8);
        List<String> bad = new ArrayList<>(orig);
        bad.add(":::not-a-path:::");
        Files.write(srcFile, bad, StandardCharsets.UTF_8);
        refreshSources();
        Thread.sleep(1200);
        AtomicBoolean warn = new AtomicBoolean();
        edt(() -> warn.set(panel().getWarningLabel().isVisible()));
        check("m01", "bad-row-warning-shown", warn.get(), "visible=" + warn.get());
        Files.write(srcFile, orig, StandardCharsets.UTF_8);
        refreshSources();
        Thread.sleep(1200);
        edt(() -> warn.set(panel().getWarningLabel().isVisible()));
        check("m01", "warning-clears-after-fix", !warn.get(), "visible=" + warn.get());

        // ---- M-01.6b: committed source removed -> Custom + next query rejected ----
        commitPath(projA.toString());
        settleSearch();
        Files.write(srcFile, Arrays.asList(projB.toString() + "|0"), StandardCharsets.UTF_8);
        refreshSources();
        Thread.sleep(1200);
        sel = comboSel();
        check("m01", "deleted-source-shows-custom", "Custom".equals(sel), "selected=" + sel);
        edt(() -> invokeDoSearch(win));
        settleSearch();
        String st = status();
        check("m01", "deleted-source-query-rejected",
                st.contains(SearchErrorMessages.forCode(SearchException.Code.DIRECTORY_NOT_INDEXED))
                        || st.contains(SearchErrorMessages.forCode(SearchException.Code.INVALID_DIRECTORY)),
                "status=" + st);
        Files.write(srcFile, Arrays.asList(projA + "|0", projB + "|0"), StandardCharsets.UTF_8);
        refreshSources();

        // ================= M-02 =================
        // relative path
        commitPath("relpath");
        settleSearch();
        st = status();
        check("m02", "relative-path-fixed-text",
                isFixedError(st), "status=" + st);
        // outside-source path (sibling not configured)
        commitPath(sibling.toString());
        settleSearch();
        st = status();
        check("m02", "outside-source-directory-not-indexed",
                st.contains(SearchErrorMessages.forCode(SearchException.Code.DIRECTORY_NOT_INDEXED))
                        || st.contains(SearchErrorMessages.forCode(SearchException.Code.INVALID_DIRECTORY)),
                "status=" + st);
        // invalid syntax without dir
        edt(() -> panel().clearDirectory());
        settleSearch();
        int hist = historySize();
        edt(() -> win.getSearchField().setText("report AND ("));
        edt(() -> invokeDoSearch(win));
        settleSearch();
        st = status();
        check("m02", "invalid-syntax-no-dir",
                st.contains(SearchErrorMessages.forCode(SearchException.Code.INVALID_QUERY)),
                "status=" + st);
        check("m02", "invalid-syntax-not-in-history", historySize() == hist,
                "history " + hist + "->" + historySize());
        // invalid syntax with dir
        commitPath(projA.toString());
        settleSearch();
        edt(() -> invokeDoSearch(win));
        settleSearch();
        st = status();
        check("m02", "invalid-syntax-with-dir",
                st.contains(SearchErrorMessages.forCode(SearchException.Code.INVALID_QUERY)),
                "status=" + st);
        edt(() -> win.getSearchField().setText("alpha"));
        // zero hits inside scope -> plain "No results" text
        edt(() -> win.getSearchField().setText("zzzz-no-hit-token"));
        edt(() -> invokeDoSearch(win));
        settleSearch();
        st = status();
        check("m02", "zero-hit-in-scope", st.startsWith("No results"), "status=" + st);
        // indexing-in-progress blocks scope action with fixed text
        setIndexing(true);
        edt(() -> win.getSearchField().setText("alpha"));
        edt(() -> invokeDoSearch(win));
        Thread.sleep(400);
        st = status();
        check("m02", "indexing-blocks-search",
                st.contains("Indexing in progress"), "status=" + st);
        setIndexing(false);
        // broken sources.txt entirely
        Files.write(srcFile, Arrays.asList(":::garbage1", "\\\\\\bad", ":::garbage2"),
                StandardCharsets.UTF_8);
        refreshSources();
        Thread.sleep(1200);
        edt(() -> invokeDoSearch(win));
        settleSearch();
        st = status();
        check("m02", "all-sources-broken", isFixedError(st), "status=" + st);
        Files.write(srcFile, Arrays.asList(projA + "|0", projB + "|0"), StandardCharsets.UTF_8);
        refreshSources();
        Thread.sleep(800);
        // ================= M-03: rapid switching, last request wins =================
        commitPath(projA.toString());
        settleSearch();
        edt(() -> {
            win.getSearchField().setText("alpha");
            invokeDoSearch(win);            // A-scope
            invokeDoSearch(win);            // A-scope again (superseded)
        });
        edt(() -> win.searchInDirectory(projB.toString())); // B-scope supersedes
        Thread.sleep(2500);
        edt(() -> { });
        check("m03", "rapid-switch-last-wins",
                hasRow("alpha-B.txt") && !hasRow("alpha-A.txt"), rowsDump());
        // ================= M-04 http surface on same real app =================
        if (web != null) {
            checkHttp("m04", "api-all", port, "/api/search?q=alpha", 200, "\"count\":4");
            checkHttp("m04", "api-dirA", port,
                    "/api/search?q=alpha&directory=" + enc(projA.toString()), 200, "\"count\":3");
            checkHttp("m04", "api-dirSub", port,
                    "/api/search?q=alpha&directory=" + enc(nested.toString()), 200, "\"count\":1");
            checkHttp("m04", "api-outside-422", port,
                    "/api/search?q=alpha&directory=" + enc(sibling.toString()), 422,
                    "DIRECTORY_NOT_INDEXED");
            checkHttp("m04", "api-invalid-syntax-400", port,
                    "/api/search?q=" + enc("report AND ("), 400, "INVALID_QUERY");
            checkHttp("m04", "api-folder-root", port,
                    "/api/search?q=ProjectA&scope=FOLDER&directory=" + enc(projA.toString()),
                    200, "\"count\":1");
            // special-encoding directory
            checkHttp("m04", "api-special-enc-dir", port,
                    "/api/search?q=alpha&directory=" + enc(special.toString()), 200, "\"count\":1");
            // legacy api: no directory param behaves as before (covered by api-all)
            // preview/open regression spot check
            checkHttp("m04", "api-text-preview", port,
                    "/api/text?path=" + enc(projA.resolve("alpha-A.txt").toString()),
                    200, "alpha contract A");
            // share create/info spot check (params are query/form, not JSON)
            HttpResp share = http("POST", port, "/api/share/create",
                    "path=" + enc(projA.resolve("alpha-A.txt").toString()));
            boolean shareOk = share.code == 200 && share.body.contains("\"token\"");
            check("m06", "api-share-create", shareOk, "code=" + share.code
                    + " body=" + share.body);
            if (shareOk) {
                String token = share.body.replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
                HttpResp info = http("GET", port, "/api/share/" + token + "/info", null);
                check("m06", "api-share-info",
                        info.code == 200 && info.body.contains("\"name\":\"alpha-A.txt\""),
                        "code=" + info.code + " body=" + info.body);
            }
        }
    }

    private static void scenarioEmpty(Path root, int port) throws Exception {
        buildFixture(root);
        // --empty: no sources, no index yet; user adds source then Index All.
        writeSources(projA, projB);
        startWindow(indexDir(), port); // open fails -> service null until indexed
        Thread.sleep(800);
        edt(() -> win.getSearchField().setText("alpha"));
        edt(() -> invokeDoSearch(win));
        settleSearch();
        String st = status();
        check("m06", "empty-env-unavailable-text",
                st.contains(SearchErrorMessages.forCode(SearchException.Code.INDEX_UNAVAILABLE)),
                "status=" + st);
        // real menu entry: File > Index All Sources
        edt(() -> win.onIndexAllSources());
        boolean done = waitUntil(() -> {
            try { String s = status();
                return s.startsWith("Indexed files:") || (s.equals("Ready") && win.getCurrentSearchService() != null); }
            catch (Exception e) { return false; }
        }, 120_000);
        check("m06", "indexall-finishes", done, "status=" + status());
        waitService();
        edt(() -> invokeDoSearch(win));
        settleSearch();
        check("m06", "search-after-indexall-no-restart",
                hasRow("alpha-A.txt") && hasRow("alpha-B.txt"), rowsDump());
        if (web != null) {
            checkHttp("m06", "web-after-indexall", port, "/api/search?q=alpha", 200, "\"count\":4");
        }
    }

    private static void scenarioStale(Path root, int port) throws Exception {
        buildFixture(root);
        writeSources(projA, projB);
        // legacy index at default dir: docs without path_scope + one ghost doc
        Path ghost = projA.resolve("ghost-legacy.txt");
        mkStaleIndex(indexDir(), ghost.toString());
        startWindow(indexDir(), port);
        waitService();
        // unscoped search still works on old data
        edt(() -> win.getSearchField().setText("alpha"));
        edt(() -> invokeDoSearch(win));
        settleSearch();
        check("m06", "stale-index-unscoped-works", rowCount() > 0, rowsDump());
        // scoped -> INDEX_REBUILD_REQUIRED, no auto-rebuild
        commitPath(projA.toString());
        settleSearch();
        String st = status();
        check("m06", "stale-index-scoped-rebuild-required",
                st.contains(SearchErrorMessages.forCode(SearchException.Code.INDEX_REBUILD_REQUIRED)),
                "status=" + st);
        if (web != null) {
            checkHttp("m06", "web-stale-409", port,
                    "/api/search?q=alpha&directory=" + enc(projA.toString()), 409,
                    "INDEX_REBUILD_REQUIRED");
        }
        // Index All (incremental) keeps ghost doc -> scoped still 409 (M-06.2b)
        edt(() -> win.onIndexAllSources());
        waitUntil(() -> {
            try { String s = status();
                return s.startsWith("Indexed files:") || (s.equals("Ready") && win.getCurrentSearchService() != null); }
            catch (Exception e) { return false; }
        }, 120_000);
        waitService();
        edt(() -> invokeDoSearch(win));
        settleSearch();
        st = status();
        check("m06", "indexall-over-stale-still-409",
                st.contains(SearchErrorMessages.forCode(SearchException.Code.INDEX_REBUILD_REQUIRED)),
                "status=" + st);
        // Full rebuild heals it
        edt(() -> win.onRebuildIndex());
        boolean ok = waitUntil(() -> {
            try { return status().startsWith("Rebuilt files:"); } catch (Exception e) { return false; }
        }, 120_000);
        check("m06", "full-rebuild-done", ok, "status=" + status());
        waitService();
        commitPath(projA.toString());
        settleSearch();
        check("m06", "scoped-works-after-full-rebuild",
                hasRow("alpha-A.txt") && hasRow("alpha-nested.txt") && !hasRow("alpha-B.txt"),
                rowsDump());
    }

    private static void scenarioRebuild(Path root, int port) throws Exception {
        // bigger fixture so the 503 window is observable
        Path big = root.resolve("source").resolve("BigSrc");
        Files.createDirectories(big);
        for (int i = 0; i < 2000; i++) {
            Files.write(big.resolve("f" + i + ".txt"),
                    ("alpha contract " + i).getBytes(StandardCharsets.UTF_8));
        }
        writeSources(big);
        indexAll(indexDir(), false);
        startWindow(indexDir(), port);
        waitService();
        if (web != null) {
            checkHttp("m06", "pre-rebuild-200", port, "/api/search?q=alpha&limit=200",
                    200, "\"count\":200");
        }
        // fire real rebuild; poll web concurrently for 503
        AtomicBoolean saw503 = new AtomicBoolean();
        AtomicBoolean sawIndexingStatus = new AtomicBoolean();
        Thread poller = new Thread(() -> {
            long end = System.currentTimeMillis() + 60_000;
            while (System.currentTimeMillis() < end) {
                try {
                    HttpResp r = http("GET", port, "/api/search?q=alpha", null);
                    if (r.code == 503) saw503.set(true);
                } catch (Exception ignore) { }
                try {
                    if (status().contains("Rebuild")) sawIndexingStatus.set(true);
                } catch (Exception ignore) { }
                if (saw503.get() && sawIndexingStatus.get()) return;
                try { Thread.sleep(30); } catch (InterruptedException ignore) { }
            }
        });
        poller.setDaemon(true);
        poller.start();
        edt(() -> win.onRebuildIndex());
        boolean ok = waitUntil(() -> win.getCurrentSearchService() != null
                && !isIndexingFlag(), 120_000);
        poller.join(5_000);
        check("m06", "rebuild-status-shown", sawIndexingStatus.get(), "");
        check("m06", "web-503-during-rebuild", saw503.get(), "");
        check("m06", "rebuild-completed", ok, "status=" + status());
        if (web != null) {
            checkHttp("m06", "post-rebuild-200", port, "/api/search?q=alpha&limit=200",
                    200, "\"count\":200");
        }
        // forced failure: lock index dir file, rebuild must fail and keep 503
        Path lockTarget = indexDir().resolve("segments_1");
        if (!Files.exists(lockTarget)) {
            java.util.Iterator<Path> it = Files.list(indexDir()).iterator();
            while (it.hasNext()) { Path p = it.next();
                if (p.getFileName().toString().startsWith("segments_")) { lockTarget = p; break; } }
        }
        java.nio.channels.FileLock lock = null;
        java.io.RandomAccessFile raf = null;
        try {
            raf = new java.io.RandomAccessFile(lockTarget.toFile(), "rw");
            lock = raf.getChannel().lock();
        } catch (Exception e) {
            check("m06", "forced-failure-lock-acquired", false, "lock failed: " + e);
        }
        AtomicBoolean saw503f = new AtomicBoolean();
        Thread poller2 = new Thread(() -> {
            long end = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < end) {
                try {
                    HttpResp r = http("GET", port, "/api/search?q=alpha", null);
                    if (r.code == 503) saw503f.set(true);
                } catch (Exception ignore) { }
                try { Thread.sleep(30); } catch (InterruptedException ignore) { }
            }
        });
        poller2.setDaemon(true);
        poller2.start();
        edt(() -> win.onRebuildIndex());
        boolean failed = waitUntil(() -> {
            try { String s = status();
                return s.contains("failed") || s.contains("unavailable"); } catch (Exception e) { return false; }
        }, 60_000);
        poller2.join(3_000);
        check("m06", "rebuild-failure-reported", failed, "status=" + status());
        check("m06", "web-503-after-failed-rebuild", saw503f.get(), "");
        if (lock != null) lock.release();
        if (raf != null) raf.close();
        // recovery: rebuild again succeeds
        edt(() -> win.onRebuildIndex());
        ok = waitUntil(() -> win.getCurrentSearchService() != null && !isIndexingFlag(),
                120_000);
        check("m06", "rebuild-recovery", ok, "status=" + status());
        if (web != null) {
            checkHttp("m06", "post-recovery-200", port, "/api/search?q=alpha&limit=200",
                    200, "\"count\":200");
        }
    }

    private static void scenarioWebDown(int port) throws Exception {
        WebServer w = new WebServer(port, "127.0.0.1");
        w.start();
        try {
            checkHttp("m04", "web-no-service-503", port, "/api/search?q=alpha", 503,
                    "INDEX_UNAVAILABLE");
        } finally {
            w.stop();
        }
    }

    private static void scenarioWebJson(Path root, int port) throws Exception {
        // Real service + real WebServer, no window: emit raw /api/search JSON so
        // the shell can validate it (F01 decimal-comma locale regression).
        buildFixture(root);
        writeSources(projA, projB);
        indexAll(indexDir(), false);
        org.abitware.docfinder.search.LuceneSearchService svc =
                new org.abitware.docfinder.search.LuceneSearchService(indexDir(),
                        new ConfigManager().loadIndexSettings());
        WebServer w = new WebServer(port, "127.0.0.1");
        w.start();
        w.setSearchService(svc);
        try {
            HttpResp r = http("GET", port, "/api/search?q=alpha&limit=200", null);
            check("m04", "locale-json-200", r.code == 200, "code=" + r.code);
            System.out.println("RAWJSON=" + r.body);
        } finally {
            w.stop();
            svc.close();
        }
    }

    /* ================= fixture / index ================= */

    private static void buildFixture(Path root) throws Exception {
        Path source = root.resolve("source");
        projA = Files.createDirectories(source.resolve("ProjectA"));
        nested = Files.createDirectories(projA.resolve("sub/deep"));
        sibling = Files.createDirectories(source.resolve("ProjectAB"));
        projB = Files.createDirectories(source.resolve("ProjectB"));
        special = Files.createDirectories(projA.resolve("资料 空格&#+[草稿]"));
        write(projA.resolve("alpha-A.txt"), "alpha contract A");
        write(nested.resolve("alpha-nested.txt"), "alpha contract nested");
        write(sibling.resolve("alpha-sibling.txt"), "alpha contract sibling");
        write(projB.resolve("alpha-B.txt"), "alpha contract B");
        write(special.resolve("alpha-special.txt"), "alpha contract special");
    }

    private static void write(Path p, String text) throws Exception {
        Files.write(p, text.getBytes(StandardCharsets.UTF_8));
    }

    private static Path indexDir() {
        return new SourceManager().getIndexDir();
    }

    private static Path sourcesFile() {
        return org.abitware.docfinder.util.AppPaths.getBaseDir().resolve("sources.txt");
    }

    private static void writeSources(Path... dirs) throws Exception {
        new SourceManager().save(Arrays.asList(dirs));
    }

    private static void indexAll(Path idx, boolean full) throws Exception {
        SourceManager sm = new SourceManager();
        try (LuceneIndexer indexer = new LuceneIndexer(idx,
                new ConfigManager().loadIndexSettings())) {
            indexer.indexFolders(sm.load(), full);
        }
    }

    private static void mkStaleIndex(Path idxDir, String ghostPath) throws Exception {
        Files.createDirectories(idxDir);
        org.apache.lucene.store.Directory dir =
                org.apache.lucene.store.FSDirectory.open(idxDir);
        org.apache.lucene.index.IndexWriter w = new org.apache.lucene.index.IndexWriter(dir,
                new org.apache.lucene.index.IndexWriterConfig(
                        new org.apache.lucene.analysis.standard.StandardAnalyzer()));
        org.apache.lucene.document.Document d = new org.apache.lucene.document.Document();
        d.add(new org.apache.lucene.document.StringField("path", ghostPath,
                org.apache.lucene.document.Field.Store.YES));
        d.add(new org.apache.lucene.document.TextField("name", "ghost-legacy.txt",
                org.apache.lucene.document.Field.Store.YES));
        d.add(new org.apache.lucene.document.StringField("name_raw", "ghost-legacy.txt",
                org.apache.lucene.document.Field.Store.YES));
        d.add(new org.apache.lucene.document.StringField("ext", "txt",
                org.apache.lucene.document.Field.Store.YES));
        d.add(new org.apache.lucene.document.StringField("kind", "file",
                org.apache.lucene.document.Field.Store.YES));
        d.add(new org.apache.lucene.document.TextField("content", "alpha ghost",
                org.apache.lucene.document.Field.Store.NO));
        d.add(new org.apache.lucene.document.StoredField("size", 11L));
        d.add(new org.apache.lucene.document.StoredField("mtime", 0L));
        d.add(new org.apache.lucene.document.StoredField("ctime", 0L));
        d.add(new org.apache.lucene.document.StoredField("atime", 0L));
        w.addDocument(d);
        w.commit();
        w.close();
        System.out.println("PROBE_INFO stale index written at " + idxDir);
    }

    private static void startWindow(Path idx, int port) throws Exception {
        if (port > 0) {
            web = new WebServer(port, "127.0.0.1");
            web.start();
        }
        SwingUtilities.invokeAndWait(() -> {
            win = new MainWindow(null);
            if (web != null) win.setWebServer(web);
            win.setVisible(true);
            win.reopenSearchServiceAsync(idx, new ConfigManager().loadIndexSettings());
        });
        waitService();
    }

    private static void waitService() throws Exception {
        waitUntil(() -> win.getCurrentSearchService() != null, 20_000);
    }

    /* ================= UI helpers ================= */

    private static DirectoryScopePanel panel() { return win.getDirectoryScopePanel(); }

    private static void edt(Runnable r) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) r.run();
        else SwingUtilities.invokeAndWait(r);
    }

    private static void invokeDoSearch(MainWindow w) {
        try {
            Method m = MainWindow.class.getDeclaredMethod("doSearch");
            m.setAccessible(true);
            m.invoke(w);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void setScope(SearchScope s) throws Exception {
        Field f = MainWindow.class.getDeclaredField("scopeBox");
        f.setAccessible(true);
        edt(() -> {
            try { ((JComboBox<?>) f.get(win)).setSelectedItem(s); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
    }

    private static void selectSource(String item) throws Exception {
        edt(() -> panel().getSourceCombo().setSelectedItem(item));
    }

    private static void commitPath(String path) throws Exception {
        edt(() -> {
            panel().getPathField().setText(path);
            panel().getPathField().postActionEvent();
        });
    }

    private static String comboSel() throws Exception {
        AtomicReference<Object> sel = new AtomicReference<>();
        edt(() -> sel.set(panel().getSourceCombo().getSelectedItem()));
        return sel.get() == null ? null : sel.get().toString();
    }

    private static void refreshSources() throws Exception {
        Method m = MainWindow.class.getDeclaredMethod("refreshSourceDirectoriesAsync");
        m.setAccessible(true);
        edt(() -> {
            try { m.invoke(win); } catch (Exception e) { throw new RuntimeException(e); }
        });
    }

    private static boolean isIndexingFlag() throws Exception {
        Field f = MainWindow.class.getDeclaredField("isIndexing");
        f.setAccessible(true);
        AtomicBoolean v = new AtomicBoolean();
        edt(() -> {
            try { v.set(f.getBoolean(win)); } catch (Exception e) { throw new RuntimeException(e); }
        });
        return v.get();
    }

    private static void setIndexing(boolean v) throws Exception {
        Field f = MainWindow.class.getDeclaredField("isIndexing");
        f.setAccessible(true);
        edt(() -> {
            try { f.setBoolean(win, v); } catch (Exception e) { throw new RuntimeException(e); }
        });
    }

    private static String status() throws Exception {
        AtomicReference<String> s = new AtomicReference<>();
        edt(() -> s.set(win.getStatusLabel().getText()));
        return s.get();
    }

    private static int rowCount() throws Exception {
        final int[] n = new int[1];
        edt(() -> n[0] = win.getResultTable().getRowCount());
        return n[0];
    }

    private static boolean hasRow(String name) throws Exception {
        final boolean[] found = new boolean[1];
        edt(() -> {
            for (int i = 0; i < win.getResultTable().getRowCount(); i++) {
                Object v = win.getResultTable().getValueAt(i, 0);
                if (name.equals(String.valueOf(v))) found[0] = true;
            }
        });
        return found[0];
    }

    private static String rowsDump() throws Exception {
        StringBuilder sb = new StringBuilder();
        edt(() -> {
            for (int i = 0; i < win.getResultTable().getRowCount(); i++) {
                if (i > 0) sb.append(',');
                sb.append(win.getResultTable().getValueAt(i, 0));
            }
        });
        return "rows[" + sb + "]";
    }

    private static String lastQuery() throws Exception {
        Field f = MainWindow.class.getDeclaredField("lastQuery");
        f.setAccessible(true);
        AtomicReference<Object> v = new AtomicReference<>();
        edt(() -> {
            try { v.set(f.get(win)); } catch (Exception e) { throw new RuntimeException(e); }
        });
        return String.valueOf(v.get());
    }

    private static int historySize() throws Exception {
        Field f = MainWindow.class.getDeclaredField("queryBox");
        f.setAccessible(true);
        final int[] n = new int[1];
        edt(() -> {
            try { n[0] = ((JComboBox<?>) f.get(win)).getModel().getSize(); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
        return n[0];
    }

    private static boolean isFixedError(String st) {
        for (SearchException.Code c : SearchException.Code.values()) {
            if (st.contains(SearchErrorMessages.forCode(c))) return true;
        }
        return false;
    }

    /** Deterministic settle: workers on the small fixture finish well under 1 s;
     *  poll until status/rows are stable across two reads. */
    private static void settleSearch() throws Exception {
        Thread.sleep(1_000);
        edt(() -> { }); // flush queued EDT work
        long end = System.currentTimeMillis() + 10_000;
        int lastRows = -1;
        String lastStatus = null;
        while (System.currentTimeMillis() < end) {
            int r = rowCount();
            String s = status();
            if (r == lastRows && s.equals(lastStatus)) return;
            lastRows = r;
            lastStatus = s;
            Thread.sleep(250);
        }
    }

    private static boolean waitUntil(Check c, long ms) throws Exception {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (c.ok()) return true;
            Thread.sleep(200);
        }
        return c.ok();
    }

    private interface Check { boolean ok() throws Exception; }

    /* ================= http helpers ================= */

    private static final class HttpResp {
        final int code; final String body;
        HttpResp(int c, String b) { code = c; body = b; }
    }

    private static HttpResp http(String method, int port, String path, String body)
            throws Exception {
        HttpURLConnection c = (HttpURLConnection)
                new URL("http://127.0.0.1:" + port + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(3_000);
        c.setReadTimeout(5_000);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            c.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        }
        int code = c.getResponseCode();
        BufferedReader br = new BufferedReader(new InputStreamReader(
                code < 400 ? c.getInputStream() : c.getErrorStream(), StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        return new HttpResp(code, sb.toString());
    }

    private static void checkHttp(String tag, String name, int port, String path,
                                  int expectCode, String expectSub) throws Exception {
        HttpResp r = http("GET", port, path, null);
        check(tag, name, r.code == expectCode && r.body.contains(expectSub),
                "code=" + r.code + " body=" + (r.body.length() > 160
                        ? r.body.substring(0, 160) : r.body));
    }

    private static String enc(String s) throws Exception {
        return URLEncoder.encode(s, "UTF-8");
    }

    /* ================= reporting ================= */

    private static void check(String tag, String name, boolean ok, String detail) {
        if (ok) passCount++; else failCount++;
        System.out.println((ok ? "PASS " : "FAIL ") + tag + " " + name + " :: " + detail);
    }
}
