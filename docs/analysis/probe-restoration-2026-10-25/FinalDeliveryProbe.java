import org.abitware.docfinder.index.*;
import org.abitware.docfinder.search.*;
import org.abitware.docfinder.util.Utils;
import org.abitware.docfinder.watch.*;
import org.abitware.docfinder.web.WebServer;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.store.FSDirectory;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.net.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Direct shaded-JAR smoke test. Run in a fresh disposable working directory.
 * Source trees deliberately sit outside user.dir; never points at user data. */
public class FinalDeliveryProbe {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        System.out.println("PASS " + message);
    }
    private static boolean indexed(Path index, Path path) throws Exception {
        try (FSDirectory directory = FSDirectory.open(index);
             DirectoryReader reader = DirectoryReader.open(directory)) {
            return new IndexSearcher(reader).count(new TermQuery(new Term("path",
                    Utils.normalizeForIndex(path)))) == 1;
        }
    }
    private static void awaitIndexed(Path index, Path path, boolean wanted) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (indexed(index, path) != wanted && System.nanoTime() < deadline) Thread.sleep(100);
        check(indexed(index, path) == wanted, "index state " + path.getFileName() + "=" + wanted);
    }
    public static void main(String[] args) throws Exception {
        Path fixture = Files.createTempDirectory("docfinder-delivery-source-");
        Path local = Files.createDirectory(fixture.resolve("local"));
        Path remote = Files.createDirectory(fixture.resolve("poll"));
        Path index = fixture.resolve("index");
        Path report = local.resolve("AnnualReport2026.txt");
        Files.write(report, "uniquefinalcontent".getBytes(StandardCharsets.UTF_8));
        IndexSettings settings = new IndexSettings();
        try (LuceneIndexer writer = new LuceneIndexer(index, settings)) { writer.indexFolder(local); }
        check(indexed(index, report), "initial file committed");
        LuceneSearchService service = new LuceneSearchService(index, settings,
                () -> Arrays.asList(local, remote));
        try {
            SearchRequest query = new SearchRequest("report", 10, null, SearchScope.NAME, MatchMode.FUZZY);
            SearchOutcome first = service.searchDetailed(query);
            System.out.println("INITIAL_SEARCH count=" + first.results.size() + " unverified=" + first.hasUnverified);
            check(first.results.size() == 1, "name substring via shaded JAR");
            check(service.searchDetailed(new SearchRequest("uniquefinalcontent", 10, null,
                    SearchScope.CONTENT, MatchMode.FUZZY)).results.size() == 1,
                    "isolated content extraction is searchable");
            Files.delete(report);
            check(service.searchDetailed(query).results.isEmpty(), "deleted hit hidden on next search");
            awaitIndexed(index, report, false);
            Files.write(report, "restored-final-content".getBytes(StandardCharsets.UTF_8));
            // Reader-only polling: restoration must happen without another search.
            awaitIndexed(index, report, true);
            check(service.searchDetailed(query).results.size() == 1, "restored hit is searchable");
            try (LiveIndexService live = new LiveIndexService(index, settings, Collections.singletonList(local))) {
                live.start();
                Path added = Files.write(local.resolve("live-added.txt"), new byte[] { 1 });
                awaitIndexed(index, added, true);
            }
            try (NetPollerService poller = new NetPollerService(index, settings, Collections.singletonList(remote))) {
                Path added = Files.write(remote.resolve("network-report.txt"), new byte[] { 1 });
                check(poller.pollNowAsync().get(30, TimeUnit.SECONDS).successful, "manual poll works without start");
                awaitIndexed(index, added, true);
                Files.delete(added);
                NetPollerService.PollStats deleted = poller.pollNowAsync().get(30, TimeUnit.SECONDS);
                check(deleted.successful && deleted.deleted >= 1, "poll deletion is applied");
                awaitIndexed(index, added, false);
            }
            WebServer web = new WebServer(0, "127.0.0.1");
            web.setSearchService(service);
            try {
                web.start();
                HttpURLConnection connection = (HttpURLConnection) new URL(web.getBaseUrl()
                        + "/api/search?q=report&scope=NAME").openConnection();
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(10000);
                try {
                    check(connection.getResponseCode() == 200, "real HTTP search returns 200");
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    try (InputStream input = connection.getInputStream()) {
                        byte[] buffer = new byte[4096];
                        for (int count; (count = input.read(buffer)) != -1;) {
                            if (bytes.size() + count > 65536) throw new IOException("Unexpected response size");
                            bytes.write(buffer, 0, count);
                        }
                    }
                    check(new String(bytes.toByteArray(), StandardCharsets.UTF_8).contains("AnnualReport2026.txt"),
                            "HTTP result contains restored file");
                } finally { connection.disconnect(); }
            } finally { web.stop(); }
        } finally { service.close(); Tombstones.close(); }
        System.out.println("FINAL_DELIVERY_PASS java=" + System.getProperty("java.version") + " fixture=" + fixture);
    }
}
