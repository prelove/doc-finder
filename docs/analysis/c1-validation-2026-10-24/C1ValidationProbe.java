import org.abitware.docfinder.index.ConfigManager;
import org.abitware.docfinder.index.IndexSettings;
import org.abitware.docfinder.index.LuceneIndexer;
import org.abitware.docfinder.watch.LiveIndexService;
import org.abitware.docfinder.watch.NetPollerService;
import org.abitware.docfinder.watch.SnapshotStore;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.store.FSDirectory;

import java.nio.file.*;
import java.util.*;

/**
 * C1 isolated validation probe. Read-only use of product classes; all mutation
 * confined to the QA root passed as args[1]. Prints [REPRO]/[NOREPRO]/[UNSUPP]/[FAIL].
 *
 * Usage: C1ValidationProbe <scenario|all> <qaRoot>
 *   qaRoot must contain work/ (== user.dir at launch), home/, source/.
 */
public class C1ValidationProbe {
    static Path qaRoot, work, home, srcRoot, indexDir;
    static IndexSettings settings;
    static int repro = 0, norepro = 0, unsupp = 0, fail = 0;

    public static void main(String[] args) throws Exception {
        String scenario = args.length > 0 ? args[0] : "all";
        qaRoot = Paths.get(args[1]).toAbsolutePath().normalize();
        work = qaRoot.resolve("work").toAbsolutePath().normalize();
        home = qaRoot.resolve("home").toAbsolutePath().normalize();
        srcRoot = qaRoot.resolve("source").toAbsolutePath().normalize();
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        if (!cwd.equals(work)) {
            System.out.println("[FATAL] isolation guard: user.dir=" + cwd + " != " + work);
            System.exit(2);
        }
        indexDir = new ConfigManager().getIndexDir();
        if (!indexDir.toAbsolutePath().normalize().startsWith(qaRoot)) {
            System.out.println("[FATAL] isolation guard: indexDir escapes QA root: " + indexDir);
            System.exit(2);
        }
        settings = new ConfigManager().loadIndexSettings();
        System.out.println("== C1ValidationProbe scenario=" + scenario + " qaRoot=" + qaRoot
                + " user.dir=" + cwd + " indexDir=" + indexDir);
        Files.createDirectories(srcRoot);

        switch (scenario) {
            case "live-newdir": s1LiveNewDir(); break;
            case "live-movedel": s2MoveDelete(); break;
            case "invalid-key": s3InvalidKey(); break;
            case "overflow": s4Overflow(); break;
            case "partial-scan": s5PartialScan(); break;
            case "write-fail": s6WriteFail(); break;
            case "snap-keys": s7SnapKeys(); break;
            case "coldstart": s8ColdStart(); break;
            case "dir-record": s9DirRecord(); break;
            case "inject": s10Inject(); break;
            default:
                s1LiveNewDir(); s2MoveDelete(); s3InvalidKey(); s4Overflow();
                s5PartialScan(); s6WriteFail(); s7SnapKeys(); s8ColdStart(); s9DirRecord();
                s10Inject();
        }
        System.out.printf("== SUMMARY repro=%d norepro=%d unsupported=%d fail=%d%n", repro, norepro, unsupp, fail);
    }

    /* ---------------- S1: new dir self-registration + dir record (D-01b/c) ---- */
    static void s1LiveNewDir() throws Exception {
        sec("S1 live-newdir (D-01b/D-01c)");
        Path root = mk("s1");
        seed(root);
        LiveIndexService live = new LiveIndexService(indexDir, settings, Arrays.asList(root));
        live.start();
        sleep(3500); // allow watcher bootstrap registration

        Path nd = guard(root.resolve("newdir"));
        Files.createDirectories(nd);
        sleep(3000);
        check(!docExists(nd), "newdir folder doc absent (waitStable rejects dirs → no dir doc)", "newdir folder doc indexed");

        Path direct = guard(nd.resolve("inside.txt"));
        write(direct, "a");
        sleep(3000);
        check(!docExists(direct), "file in newdir NOT indexed (newdir itself unregistered)", "file in newdir indexed");

        Path sub = guard(nd.resolve("sub"));
        Files.createDirectories(sub);
        sleep(3000); // sub gets registered by bootstrap walk (non-root dirs are registered)
        Path deep = guard(sub.resolve("deep.txt"));
        write(deep, "b");
        sleep(3000);
        check(docExists(deep), "file in newdir/sub indexed (deeper dirs DO get keys)", "file in newdir/sub missed");
        live.close();
    }

    /* ---------------- S2: dir move + delete (D-01a) ------------------------- */
    static void s2MoveDelete() throws Exception {
        sec("S2 live-movedel (D-01a)");
        Path root = mk("s2");
        Path mv = guard(root.resolve("mv"));
        Files.createDirectories(mv);
        write(guard(mv.resolve("f1.txt")), "1");
        write(guard(mv.resolve("f2.txt")), "2");
        seed(root);
        check(docExists(mv.resolve("f1.txt")), "seed f1 indexed", "seed f1 missing");
        LiveIndexService live = new LiveIndexService(indexDir, settings, Arrays.asList(root));
        live.start();
        sleep(3500);

        Path mv2 = guard(root.resolve("mv2"));
        Files.move(mv, mv2);
        sleep(4000);
        check(docExists(mv.resolve("f1.txt")), "OLD path doc still present (ghost, no cascade)", "old path doc removed");
        check(!docExists(mv2.resolve("f1.txt")), "NEW path file NOT indexed after move", "new path file indexed");
        check(!docExists(mv2), "mv2 folder doc absent", "mv2 folder doc present");

        deleteTree(mv2);
        sleep(4000);
        check(docExists(mv.resolve("f1.txt")), "docs persist after tree delete (ghost index)", "docs removed after delete");
        live.close();
    }

    /* ---------------- S3: invalid key / recreate subdir (D-03a/b + CR-01) ---- */
    static void s3InvalidKey() throws Exception {
        sec("S3 invalid-key recreate (D-03, CR-01)");
        Path root = mk("s3");
        Path sd = guard(root.resolve("sd"));
        Files.createDirectories(sd);
        write(guard(sd.resolve("before.txt")), "x");
        seed(root);
        LiveIndexService live = new LiveIndexService(indexDir, settings, Arrays.asList(root));
        live.start();
        sleep(3500);

        write(guard(sd.resolve("live1.txt")), "y");
        sleep(3000);
        check(docExists(sd.resolve("live1.txt")), "sd monitored before delete", "sd not monitored initially");

        deleteTree(sd);
        sleep(2500); // let DELETE flush; sd's own key dies with the dir
        Files.createDirectories(sd);
        sleep(2500); // parent ENTRY_CREATE → registerExistingSubDirs(sd) skips sd itself
        write(guard(sd.resolve("after.txt")), "z");
        sleep(3000);
        check(!docExists(sd.resolve("after.txt")),
                "recreated sd: file NOT indexed (sd self never re-registered despite live parent key)",
                "recreated sd file indexed");
        live.close();
    }

    /* ---------------- S4: OVERFLOW platform observation (D-02) --------------- */
    static void s4Overflow() throws Exception {
        sec("S4 overflow flood observation (D-02)");
        Path root = mk("s4");
        int n = 3000;
        Path fld = guard(root.resolve("flood"));
        Files.createDirectories(fld); // pre-create so bootstrap registers its watch key (avoid D-01b confound)
        seed(root);
        LiveIndexService live = new LiveIndexService(indexDir, settings, Arrays.asList(root));
        live.start();
        sleep(3500);
        long t0 = System.currentTimeMillis();
        for (int i = 0; i < n; i++) {
            Files.write(fld.resolve(String.format("f%05d.tmp2", i)), new byte[]{1});
        }
        long createdMs = System.currentTimeMillis() - t0;

        // wait until flush catches up or 60s cap
        long deadline = System.currentTimeMillis() + 60000;
        int got = 0;
        while (System.currentTimeMillis() < deadline) {
            got = countDocs(fld);
            if (got >= n) break;
            sleep(2000);
        }
        live.close();
        System.out.println("    flood: created=" + n + " in " + createdMs + "ms, indexed=" + got);
        if (got >= n) {
            norepro++; System.out.println("  [NOREPRO] all flooded files indexed on this host (overflow not triggered or recovered silently)");
        } else {
            repro++; System.out.println("  [REPRO] " + (n - got) + " files never indexed after flood (event loss consistent with D-02; mechanism OVERFLOW vs other gap not distinguishable without seam)");
        }
        System.out.println("  [UNSUPP] deterministic OVERFLOW injection requires a test seam (WatchService buffer cannot be overflowed on demand); flood is platform observation only");
        unsupp++;
    }

    /* ---------------- S5: partial scan misdelete + recovery (D-07) ----------- */
    static void s5PartialScan() throws Exception {
        sec("S5 partial-scan (D-07 + recovery)");
        Path root = mk("s5");
        Path keep = guard(root.resolve("keep")), blocked = guard(root.resolve("blocked"));
        Files.createDirectories(keep); Files.createDirectories(blocked);
        write(guard(keep.resolve("k1.txt")), "k");
        Path b1 = guard(blocked.resolve("b1.txt")); write(b1, "b");
        NetPollerService poller = new NetPollerService(indexDir, settings, Arrays.asList(root));
        NetPollerService.PollStats st1 = poller.pollNowAsync().get(); // real manual path (MainWindow.pollOnceNow uses pollNowAsync; pollNow() no-ops when !running)
        System.out.println("    poll1 stats: scanned=" + st1.scannedFiles + " created=" + st1.created + " deleted=" + st1.deleted);
        check(st1.scannedFiles >= 2, "poll1 executed (scanned=" + st1.scannedFiles + ")", "poll1 stats show no scan");
        check(docExists(b1), "b1 indexed after poll1", "b1 missing after poll1");

        String user = System.getProperty("user.name");
        boolean denied = false;
        try {
            denied = acl(blocked, "/deny", user + ":(OI)(CI)(RD)");
            if (!denied) { unsupp++; System.out.println("  [UNSUPP] icacls deny failed on this host"); return; }
            NetPollerService.PollStats st2 = poller.pollNowAsync().get();
            System.out.println("    poll2 stats: scanned=" + st2.scannedFiles + " deleted=" + st2.deleted);
            check(!docExists(b1), "blocked subtree doc DELETED by partial scan", "blocked doc survived partial scan");

            acl(blocked, "/remove:d", user); denied = false;
            NetPollerService.PollStats st3 = poller.pollNowAsync().get();
            System.out.println("    poll3 stats: scanned=" + st3.scannedFiles + " created=" + st3.created);
            check(docExists(b1), "b1 re-indexed after ACL restore (conditional recovery confirmed)", "b1 NOT recovered after restore");
        } finally {
            if (denied) acl(blocked, "/remove:d", user);
            acl(blocked, "/reset", "/T", "/C");
        }
        poller.close();
    }

    /* ---------------- S6: single-point write failure persistence (D-08) ------ */
    static void s6WriteFail() throws Exception {
        sec("S6 write-fail persistence (D-08 analog)");
        Path root = mk("s6");
        // file excluded by isExcluded (~ prefix / .tmp suffix) but recorded in snapshot
        Path ex = guard(root.resolve("~skip.tmp")); write(ex, "e");
        NetPollerService poller = new NetPollerService(indexDir, settings, Arrays.asList(root));
        NetPollerService.PollStats st = poller.pollNowAsync().get();
        System.out.println("    poll stats: scanned=" + st.scannedFiles + " created=" + st.created);
        boolean inSnap = new SnapshotStore().load(root).containsKey(ex.toAbsolutePath().toString());
        check(st.scannedFiles >= 1 && !docExists(ex) && inSnap,
                "[analog only] excluded file in snapshot but never indexed; o==n forever (D-08 supporting evidence — real write-fail not injected)",
                "excluded-file edge behaved differently");
        poller.close();
    }

    /* ---------------- S7: snapshot key collision + | format (R-05/CR-05) ----- */
    static void s7SnapKeys() throws Exception {
        sec("S7 snap-keys collision + pipe format");
        Path base = mk("s7");
        Path r1 = guard(base.resolve("a").resolve("b"));   // -> key a_b style
        Path r2 = guard(base.resolve("a_b"));
        Files.createDirectories(r1); Files.createDirectories(r2);
        SnapshotStore store = new SnapshotStore();
        Map<String, SnapshotStore.Entry> m1 = new HashMap<>(), m2 = new HashMap<>();
        m1.put(r1.resolve("one.txt").toString(), new SnapshotStore.Entry(1, 11));
        m2.put(r2.resolve("two.txt").toString(), new SnapshotStore.Entry(2, 22));
        store.save(r1, m1);
        store.save(r2, m2); // collision: overwrites r1's snapshot file
        Map<String, SnapshotStore.Entry> back1 = store.load(r1);
        boolean collision = !back1.containsKey(r1.resolve("one.txt").toString());
        check(collision, "keyForRoot collision: a\\b and a_b share one snapshot file (r1 data lost)", "no collision");

        // pipe-in-path line: inject malformed snapshot line manually (Windows cannot create | names)
        Path snapDir = work.resolve(".docfinder").resolve("snapshots");
        Path r3 = guard(base.resolve("p3")); Files.createDirectories(r3);
        store.save(r3, new HashMap<>());
        Path f = snapDir.resolve(fileNameFor(r3));
        if (f == null || !Files.exists(f)) {
            // fallback: locate any file created for r3
            try (java.util.stream.Stream<Path> s = Files.list(snapDir)) {
                f = s.filter(p -> p.getFileName().toString().endsWith(".txt") && p.getFileName().toString().contains("p3")).findFirst().orElse(null);
            }
        }
        if (f != null) {
            Files.write(f, Arrays.asList("C:\\aa|bb.txt|9|99"), StandardOpenOption.TRUNCATE_EXISTING);
            Map<String, SnapshotStore.Entry> back3 = store.load(r3);
            SnapshotStore.Entry e = back3.get("C:\\aa");
            boolean misalign = e != null && e.size == 0L;
            check(misalign, "pipe-path line parsed as truncated path + size=0 (silent corruption, not skipped)", "pipe line handled differently");
        } else {
            unsupp++; System.out.println("  [UNSUPP] could not locate snapshot file for pipe test");
        }
    }

    /* ---------------- S8: cold start gap (D-04) ------------------------------ */
    static void s8ColdStart() throws Exception {
        sec("S8 coldstart no-reconcile (D-04)");
        Path root = mk("s8");
        Path keep1 = guard(root.resolve("k1.txt")), del1 = guard(root.resolve("d1.txt"));
        write(keep1, "v1"); write(del1, "d");
        seed(root);
        LiveIndexService live = new LiveIndexService(indexDir, settings, Arrays.asList(root));
        live.start(); sleep(2500); live.close(); // graceful stop of isolated instance

        write(keep1, "v2-changed-size");           // modify while off
        Files.delete(del1);                         // delete while off
        write(guard(root.resolve("new.txt")), "n"); // create while off

        LiveIndexService live2 = new LiveIndexService(indexDir, settings, Arrays.asList(root));
        live2.start(); sleep(4000); live2.close();

        check(!docExists(root.resolve("new.txt")), "file created while off NOT indexed after restart (no reconcile)", "new file reconciled");
        check(docExists(del1), "deleted file doc remains after restart (ghost)", "deleted doc reconciled");
        Long sz = docSize(keep1);
        boolean stale = sz != null && sz.longValue() != Files.size(keep1);
        check(stale, "modified file doc keeps stale size=" + sz + " vs actual " + Files.size(keep1), "modification reconciled");
        System.out.println("  [UNSUPP] toggle persistence (poll.enabled) is UI-layer; not exercised headless");
        unsupp++;
    }

    /* ---------------- S9: net poller dir records (D-06) ---------------------- */
    static void s9DirRecord() throws Exception {
        sec("S9 net-poller dir records (D-06)");
        Path root = mk("s9");
        Path nd = guard(root.resolve("nd"));
        Files.createDirectories(nd);
        write(guard(nd.resolve("f.txt")), "f");
        seed(root); // full index writes folder docs
        check(docExists(nd), "nd folder doc exists after full index", "nd folder doc missing after full index");

        NetPollerService poller = new NetPollerService(indexDir, settings, Arrays.asList(root));
        Path nd2 = guard(root.resolve("nd2"));
        Files.createDirectories(nd2);
        NetPollerService.PollStats stA = poller.pollNowAsync().get();
        System.out.println("    pollA stats: scanned=" + stA.scannedFiles);
        check(stA.scannedFiles >= 1 && !docExists(nd2), "new EMPTY dir never gets folder doc via poller (poll executed, scanned=" + stA.scannedFiles + ")", "nd2 folder doc created");

        deleteTree(nd);
        NetPollerService.PollStats stB = poller.pollNowAsync().get();
        System.out.println("    pollB stats: scanned=" + stB.scannedFiles + " deleted=" + stB.deleted);
        check(docExists(nd), "deleted dir folder doc remains after poll", "deleted dir doc removed");
        poller.close();
    }

    /* ---------------- S10: component injection (key2dir dead-registration + OVERFLOW) ----
     * No product changes. key2dir is a private non-final ConcurrentHashMap on
     * LocalRecursiveWatcher — entry removal simulates a dead/lost registration.
     * OVERFLOW injection attempts sun.nio.fs.AbstractWatchKey.signalEvent via
     * --add-opens java.base/sun.nio.fs=ALL-UNNAMED; falls back to UNSUPP. */
    static void s10Inject() throws Exception {
        sec("S10 component injection (dead-registration + OVERFLOW)");
        Path root = mk("s10");
        Path wd = guard(root.resolve("wd")), wd2 = guard(root.resolve("wd2"));
        Files.createDirectories(wd); Files.createDirectories(wd2);
        seed(root);
        LiveIndexService live = new LiveIndexService(indexDir, settings, Arrays.asList(root));
        live.start(); sleep(3500);

        // control: poll until watcher proves live (deadline, not fixed sleep)
        Path pre = guard(wd.resolve("pre.txt"));
        write(pre, "x");
        boolean alive = waitDoc(pre, 20000);
        check(alive, "control file indexed (watcher live)", "control file NOT indexed within 20s");
        if (!alive) { System.out.println("  [FAIL] watcher never became live — S10 aborted"); fail++; live.close(); return; }

        Object watcher = getField(live, "watcher");
        if (watcher == null) {
            unsupp++; System.out.println("  [UNSUPP] cannot reach LiveIndexService.watcher field");
            live.close(); return;
        }
        @SuppressWarnings("unchecked")
        Map<WatchKey, Path> key2dir = (Map<WatchKey, Path>) getField(watcher, "key2dir");
        WatchKey wdKey = null;
        for (Map.Entry<WatchKey, Path> e : key2dir.entrySet()) if (wd.equals(e.getValue())) wdKey = e.getKey();
        if (wdKey == null) {
            unsupp++; System.out.println("  [UNSUPP] wd has no key2dir entry (unexpected)");
            live.close(); return;
        }

        // (a) dead-registration: remove wd's key2dir entry; the real WatchKey stays
        //     valid in ws → its CREATE events hit the dir==null branch silently
        key2dir.remove(wdKey);
        write(guard(wd.resolve("silent.txt")), "z"); sleep(3500);
        boolean dropped = !docExists(wd.resolve("silent.txt"));
        boolean stillGone = key2dir.values().stream().noneMatch(wd::equals);
        check(dropped && stillGone,
                "[component-injected] live key with missing key2dir entry: events silently dropped (dir==null → reset+continue), no re-register",
                "dir==null branch behaved differently");

        // (b) dead-key residue: delete wd2 while watched; key2dir keeps mapping (reset() return ignored)
        deleteTree(wd2);
        sleep(3000);
        boolean residue = key2dir.values().stream().anyMatch(wd2::equals);
        check(residue, "key2dir keeps stale mapping after watched dir deleted (reset() return ignored, residue observed)",
                "key2dir cleaned on delete");

        // (c) OVERFLOW inject LAST (if the injected event stalls the loop it can
        //     only mask later checks) — verify loop liveness with a fresh file
        int before = countDocs(wd2.getParent()); // root subtree count pre-injection
        boolean injected = injectOverflow(watcher, wdKey);
        if (injected) {
            sleep(1500); // let the loop take() and process the injected OVERFLOW
            Path post = guard(root.resolve("post-overflow.txt")); // parent of wd still registered
            write(post, "y");
            boolean postOk = waitDoc(post, 15000);
            check(postOk && countDocs(root) >= before,
                    "[component-injected] OVERFLOW dequeued and silently skipped; no rescan side-effect; loop still processes real events",
                    "[component-injected] loop stalled after injected OVERFLOW or index shrank");
        } else {
            unsupp++;
            System.out.println("  [UNSUPP] OVERFLOW injection failed — minimal seam: package-private signalHandler(Kind,Path) "
                    + "on LocalRecursiveWatcher, or extract event dispatch for unit tests");
        }
        live.close();
    }

    static boolean waitDoc(Path p, long ms) throws Exception {
        long deadline = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < deadline) {
            if (docExists(p)) return true;
            sleep(1000);
        }
        return false;
    }

    /* deep-reflection OVERFLOW inject: needs --add-opens java.base/sun.nio.fs=ALL-UNNAMED */
    static boolean injectOverflow(Object watcher, WatchKey key) {
        try {
            Class<?> awk = Class.forName("sun.nio.fs.AbstractWatchKey");
            java.lang.reflect.Method sig = awk.getDeclaredMethod("signalEvent", WatchEvent.Kind.class, Object.class);
            sig.setAccessible(true);
            sig.invoke(key, StandardWatchEventKinds.OVERFLOW, null);
            try {
                java.lang.reflect.Method signal = awk.getDeclaredMethod("signal");
                signal.setAccessible(true);
                signal.invoke(key);
            } catch (NoSuchMethodException nsme) {
                // fallback: enqueue directly into AbstractWatchService.pendingKeys
                Object ws = getField(watcher, "ws");
                Class<?> aws = Class.forName("sun.nio.fs.AbstractWatchService");
                java.lang.reflect.Method enq = aws.getDeclaredMethod("enqueueKey", WatchKey.class);
                enq.setAccessible(true);
                enq.invoke(ws, key);
            }
            return true;
        } catch (Throwable t) {
            System.out.println("  [inject-debug] overflow inject failed: " + t);
            return false;
        }
    }

    static Object getField(Object o, String name) {
        try {
            java.lang.reflect.Field f = o.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(o);
        } catch (Throwable t) { return null; }
    }

    /* ================= helpers ================= */
    static void sec(String s) { System.out.println("\n---- " + s); }
    static void check(boolean cond, String expect, String other) {
        if (cond) { repro++; System.out.println("  [REPRO] " + expect); }
        else { norepro++; System.out.println("  [NOREPRO] " + other); }
    }
    static Path mk(String name) throws Exception {
        Path p = guard(srcRoot.resolve(name));
        Files.createDirectories(p);
        return p;
    }
    static Path guard(Path p) {
        Path n = p.toAbsolutePath().normalize();
        if (!n.startsWith(qaRoot)) throw new IllegalStateException("isolation guard: " + n);
        return n;
    }
    static void write(Path p, String s) throws Exception {
        guard(p); Files.write(p, s.getBytes("UTF-8"));
    }
    static void seed(Path root) throws Exception {
        try (LuceneIndexer idx = new LuceneIndexer(indexDir, settings)) {
            idx.indexFolders(Arrays.asList(root), false);
        }
    }
    static void deleteTree(Path p) throws Exception {
        guard(p);
        if (!Files.exists(p)) return;
        Files.walkFileTree(p, new SimpleFileVisitor<Path>() {
            public FileVisitResult visitFile(Path f, java.nio.file.attribute.BasicFileAttributes a) throws java.io.IOException {
                Files.deleteIfExists(f); return FileVisitResult.CONTINUE;
            }
            public FileVisitResult postVisitDirectory(Path d, java.io.IOException e) throws java.io.IOException {
                Files.deleteIfExists(d); return FileVisitResult.CONTINUE;
            }
        });
    }
    static boolean docExists(Path p) throws Exception {
        String norm = p.toAbsolutePath().toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        return findDoc(norm) != null;
    }
    static Long docSize(Path p) throws Exception {
        String norm = p.toAbsolutePath().toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        Document d = findDoc(norm);
        if (d == null) return null;
        String v = d.get("size");
        try { return v == null ? null : Long.parseLong(v); } catch (Exception e) { return null; }
    }
    static int countDocs(Path under) throws Exception {
        String norm = under.toAbsolutePath().toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        int c = 0;
        try (IndexReader r = DirectoryReader.open(FSDirectory.open(indexDir))) {
            org.apache.lucene.util.Bits live = org.apache.lucene.index.MultiBits.getLiveDocs(r);
            for (int i = 0; i < r.maxDoc(); i++) {
                if (live != null && !live.get(i)) continue; // skip deleted-but-unmerged docs
                String path = r.document(i).get("path");
                if (path != null && path.replace('\\', '/').toLowerCase(Locale.ROOT).startsWith(norm + "/")) c++;
            }
        }
        return c;
    }
    static Document findDoc(String normPath) throws Exception {
        try (IndexReader r = DirectoryReader.open(FSDirectory.open(indexDir))) {
            org.apache.lucene.util.Bits live = org.apache.lucene.index.MultiBits.getLiveDocs(r);
            for (int i = 0; i < r.maxDoc(); i++) {
                if (live != null && !live.get(i)) continue; // skip deleted-but-unmerged docs
                Document d = r.document(i);
                String path = d.get("path");
                if (path != null && path.replace('\\', '/').toLowerCase(Locale.ROOT).equals(normPath)) return d;
            }
        }
        return null;
    }
    static String fileNameFor(Path root) {
        String raw = root.toAbsolutePath().toString();
        String norm = raw.replace('\\', '/').toLowerCase(Locale.ROOT);
        // mirror SnapshotStore.keyForRoot (Windows canonical variant)
        norm = norm.replace(':', '_').replace('/', '_');
        return (norm.isEmpty() ? "root" : norm) + ".txt";
    }
    static boolean acl(Path dir, String... args) {
        guard(dir);
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add("icacls"); cmd.add(dir.toString());
            cmd.addAll(Arrays.asList(args));
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), "GBK");
            int rc = p.waitFor();
            System.out.println("    icacls " + String.join(" ", args) + " rc=" + rc);
            return rc == 0;
        } catch (Exception e) {
            System.out.println("    icacls failed: " + e.getMessage());
            return false;
        }
    }
    static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) {} }
}
