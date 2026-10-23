package org.abitware.docfinder.ui;

import org.abitware.docfinder.index.ConfigManager;
import org.abitware.docfinder.index.LuceneIndexer;
import org.abitware.docfinder.index.SourceManager;
import org.abitware.docfinder.web.WebServer;

import javax.swing.SwingUtilities;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

/**
 * Manual acceptance entry point (M-01..M-03, M-06): shows the real MainWindow
 * over a generated fixture. Usage: DirectoryScopeDesktopHarness &lt;root&gt; [webPort] [--empty]
 * The JVM working directory MUST be &lt;root&gt;/work, because all app data lives in
 * user.dir/.docfinder; this refuses to run anywhere else so the real data is never touched.
 * --empty skips fixture sources/index (first-time Index All scenario).
 */
public final class DirectoryScopeDesktopHarness {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            throw new IllegalArgumentException("usage: <root> [webPort] [--empty]");
        }
        Path root = Paths.get(args[0]).toAbsolutePath().normalize();
        Path work = root.resolve("work");
        Path actual = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        if (!actual.equals(work)) {
            throw new IllegalArgumentException("Isolated work directory required: cwd must be " + work);
        }
        boolean empty = Arrays.asList(args).contains("--empty");
        Integer webPort = null;
        for (int i = 1; i < args.length; i++) {
            if (args[i].matches("\\d+")) {
                webPort = Integer.valueOf(args[i]);
            }
        }

        Path source = root.resolve("source");
        Path sourceA = Files.createDirectories(source.resolve("ProjectA"));
        Path nested = Files.createDirectories(sourceA.resolve("sub/deep"));
        Path sibling = Files.createDirectories(source.resolve("ProjectAB"));
        Path sourceB = Files.createDirectories(source.resolve("ProjectB"));
        write(sourceA.resolve("alpha-A.txt"), "alpha contract A");
        write(nested.resolve("alpha-nested.txt"), "alpha contract nested");
        write(sibling.resolve("alpha-sibling.txt"), "alpha contract sibling");
        write(sourceB.resolve("alpha-B.txt"), "alpha contract B");

        SourceManager sm = new SourceManager();
        if (!empty) {
            sm.save(Arrays.asList(sourceA, sourceB));
            try (LuceneIndexer indexer = new LuceneIndexer(sm.getIndexDir(), new ConfigManager().loadIndexSettings())) {
                indexer.indexFolders(Arrays.asList(sourceA, sourceB), false);
            }
        }
        System.out.println("ROOT=" + root);
        System.out.println("SOURCE_TREE=" + source + " (ProjectA, ProjectA/sub/deep, ProjectAB, ProjectB)");
        System.out.println("DATA_DIR=" + work.resolve(".docfinder"));

        final WebServer web = webPort == null ? null : new WebServer(webPort, "127.0.0.1");
        if (web != null) {
            web.start();
            System.out.println("WEB=http://127.0.0.1:" + webPort + "/");
        }
        SwingUtilities.invokeLater(() -> {
            MainWindow win = new MainWindow(null);
            win.setWebServer(web);
            win.setVisible(true);
            win.reopenSearchServiceAsync(sm.getIndexDir(), new ConfigManager().loadIndexSettings());
        });
    }

    private static void write(Path p, String text) throws Exception {
        Files.write(p, text.getBytes(StandardCharsets.UTF_8));
    }

    private DirectoryScopeDesktopHarness() {
    }
}
