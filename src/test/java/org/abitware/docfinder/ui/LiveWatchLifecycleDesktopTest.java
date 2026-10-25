package org.abitware.docfinder.ui;

import org.abitware.docfinder.index.ConfigManager;
import org.abitware.docfinder.index.LuceneIndexer;
import org.abitware.docfinder.index.SourceManager;
import org.abitware.docfinder.util.AppPaths;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.*;
import java.awt.GraphicsEnvironment;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/** Proves default startup and uncheck through real filesystem -> Lucene writes. */
class LiveWatchLifecycleDesktopTest {
    @TempDir Path temp;
    private MainWindow window;
    private byte[] previousSources;
    private byte[] previousConfig;
    private Path sourcesFile;
    private Path configFile;
    private Path index;
    private Path source;

    @BeforeEach void prepareIsolatedSettings() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display");
        Path base = AppPaths.getBaseDir();
        assertTrue(base.toString().contains("target"), "test must use the isolated Surefire working directory");
        Files.createDirectories(base);
        sourcesFile = base.resolve("sources.txt");
        configFile = base.resolve("config.properties");
        previousSources = Files.exists(sourcesFile) ? Files.readAllBytes(sourcesFile) : null;
        previousConfig = Files.exists(configFile) ? Files.readAllBytes(configFile) : null;
        source = Files.createDirectory(temp.resolve("source"));
        index = Files.createDirectory(temp.resolve("index"));
        // A real built index also initializes Tika's service providers. Keep
        // their cold startup outside the bounded filesystem-event wait.
        try (LuceneIndexer indexer = new LuceneIndexer(index, new ConfigManager().loadIndexSettings())) {
            indexer.commit();
        }
        new ConfigManager().setIndexDir(index);
        new SourceManager().saveEntries(Collections.singletonList(new SourceManager.SourceEntry(source.toString(), false)));
    }

    @AfterEach void restoreSettings() throws Exception {
        if (window != null) SwingUtilities.invokeAndWait(window::dispose);
        if (sourcesFile != null) restore(sourcesFile, previousSources);
        if (configFile != null) restore(configFile, previousConfig);
    }

    @Test void shouldStartDefaultWatchAndStopIndexingAfterUncheck() throws Exception {
        SwingUtilities.invokeAndWait(() -> window = new MainWindow(r -> Collections.emptyList()));
        awaitWatchStatus("Live watch: ON", 8000);
        Files.write(source.resolve("observed.txt"), Collections.singleton("observed"));
        awaitIndexed("observed.txt", 8000);
        SwingUtilities.invokeAndWait(() -> {
            JCheckBoxMenuItem toggle = (JCheckBoxMenuItem) field("liveWatchToggle");
            assertTrue(toggle.isSelected());
            toggle.doClick();
        });
        awaitWatchStatus("Live watch: OFF", 8000);
        Files.write(source.resolve("after-stop.txt"), Collections.singleton("stopped"));
        Thread.sleep(1700); // longer than two real 700ms watch flush periods
        assertFalse(isIndexed("after-stop.txt"), "unchecked watch must release its watcher and stop indexing new files");
    }

    private void awaitWatchStatus(String prefix, long timeout) throws Exception {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
        while (System.nanoTime() < end) {
            final String[] text = {null};
            SwingUtilities.invokeAndWait(() -> text[0] = ((JLabel) field("watchStatusLabel")).getText());
            if (text[0].startsWith(prefix)) return;
            Thread.sleep(30);
        }
        fail("watch never reported " + prefix);
    }
    private void awaitIndexed(String name, long timeout) throws Exception {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
        while (System.nanoTime() < end) {
            if (isIndexed(name)) return;
            Thread.sleep(50);
        }
        fail("default watch did not index " + name);
    }
    private boolean isIndexed(String name) throws Exception {
        try (FSDirectory directory = FSDirectory.open(index); DirectoryReader reader = DirectoryReader.open(directory)) {
            for (int i = 0; i < reader.maxDoc(); i++) {
                if (reader.document(i).get("path").endsWith(name)) return true;
            }
        }
        return false;
    }
    private Object field(String name) {
        try { Field f = MainWindow.class.getDeclaredField(name); f.setAccessible(true); return f.get(window); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    private static void restore(Path file, byte[] content) throws Exception {
        if (content == null) Files.deleteIfExists(file); else Files.write(file, content);
    }
}
