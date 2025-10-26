package org.abitware.docfinder.ui.workers;

import java.nio.file.Path;
import java.util.List;
import javax.swing.JFrame;
import javax.swing.SwingWorker;
import org.abitware.docfinder.index.ConfigManager;
import org.abitware.docfinder.index.IndexSettings;
import org.abitware.docfinder.index.LuceneIndexer;
import org.abitware.docfinder.index.SourceManager;

/**
 * Helper class for managing indexing operations with background workers.
 */
public class IndexingManager {

    private final JFrame parent;

    public IndexingManager(JFrame parent) {
        this.parent = parent;
    }

    /**
     * Index a single folder in the background.
     */
    public void indexFolder(Path folder, Path indexDir, IndexCallback callback) {
        new SwingWorker<Integer, Void>() {
            @Override
            protected Integer doInBackground() throws Exception {
                ConfigManager cm = new ConfigManager();
                IndexSettings s = cm.loadIndexSettings();
                LuceneIndexer idx = new LuceneIndexer(indexDir, s);
                return idx.indexFolder(folder);
            }

            @Override
            protected void done() {
                try {
                    int n = get();
                    callback.onSuccess(n, indexDir);
                } catch (Exception ex) {
                    callback.onError(ex);
                }
            }
        }.execute();
    }

    /**
     * Index all configured sources in the background.
     */
    public void indexAllSources(List<Path> sources, Path indexDir, IndexCallback callback) {
        new SwingWorker<Integer, Void>() {
            @Override
            protected Integer doInBackground() throws Exception {
                ConfigManager cm = new ConfigManager();
                IndexSettings s = cm.loadIndexSettings();
                LuceneIndexer idx = new LuceneIndexer(indexDir, s);

                int total = 0;
                for (Path p : sources) {
                    total += idx.indexFolder(p);
                }
                return total;
            }

            @Override
            protected void done() {
                try {
                    int n = get();
                    callback.onSuccess(n, indexDir);
                } catch (Exception ex) {
                    callback.onError(ex);
                }
            }
        }.execute();
    }

    /**
     * Rebuild the entire index from scratch.
     */
    public void rebuildIndex(List<Path> sources, Path indexDir, IndexCallback callback) {
        new SwingWorker<Integer, Void>() {
            @Override
            protected Integer doInBackground() throws Exception {
                ConfigManager cm = new ConfigManager();
                IndexSettings s = cm.loadIndexSettings();
                LuceneIndexer idx = new LuceneIndexer(indexDir, s);
                return idx.indexFolders(sources, true); // full rebuild
            }

            @Override
            protected void done() {
                try {
                    int n = get();
                    callback.onSuccess(n, indexDir);
                } catch (Exception ex) {
                    callback.onError(ex);
                }
            }
        }.execute();
    }

    /**
     * Callback interface for indexing operations.
     */
    public interface IndexCallback {
        void onSuccess(int filesIndexed, Path indexDir);
        void onError(Exception ex);
    }
}

