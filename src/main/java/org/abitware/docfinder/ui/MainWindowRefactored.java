package org.abitware.docfinder.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.*;
import org.abitware.docfinder.index.ConfigManager;
import org.abitware.docfinder.index.IndexSettings;
import org.abitware.docfinder.index.LuceneIndexer;
import org.abitware.docfinder.index.SourceManager;
import org.abitware.docfinder.search.FilterState;
import org.abitware.docfinder.search.LuceneSearchService;
import org.abitware.docfinder.search.MatchMode;
import org.abitware.docfinder.search.SearchResult;
import org.abitware.docfinder.search.SearchScope;
import org.abitware.docfinder.search.SearchService;
import org.abitware.docfinder.ui.components.MenuBarPanel;
import org.abitware.docfinder.ui.components.PreviewPanel;
import org.abitware.docfinder.ui.components.ResultsPanel;
import org.abitware.docfinder.ui.components.SearchPanel;
import org.abitware.docfinder.ui.components.StatusBarPanel;
import org.abitware.docfinder.ui.workers.SearchWorker;
import org.abitware.docfinder.watch.LiveIndexService;
import org.abitware.docfinder.watch.NetPollerService;

/**
 * Main application window for DocFinder.
 * This class has been refactored to use component-based architecture,
 * separating UI concerns into dedicated panels.
 * 
 * @author DocFinder Team
 * @version 1.0
 * @since 1.0
 */
public class MainWindowRefactored extends JFrame {
    
    /** Service for handling search operations */
    private SearchService searchService;
    
    /** UI Components */
    private SearchPanel searchPanel;
    private ResultsPanel resultsPanel;
    private PreviewPanel previewPanel;
    private StatusBarPanel statusBarPanel;
    private MenuBarPanel menuBarPanel;
    
    /** Background services */
    private LiveIndexService liveService;
    private NetPollerService netPoller;
    private JCheckBoxMenuItem liveWatchToggle;
    private JCheckBoxMenuItem netPollToggle;
    
    /** Search management */
    private SearchWorker activeSearchWorker;
    private final AtomicLong searchSequence = new AtomicLong(0);
    
    /** Constants */
    private static final String APP_TITLE = "DocFinder";
    private static final int MIN_WIDTH = 900;
    private static final int MIN_HEIGHT = 560;
    
    /**
     * Constructs the main window with all UI components and services.
     * 
     * @param searchService the search service to use for queries
     */
    public MainWindowRefactored(SearchService searchService) {
        super(APP_TITLE);
        this.searchService = searchService;
        
        initializeWindow();
        createComponents();
        layoutComponents();
        setupEventHandlers();
        setupIcons();
        
        // Initialize preview with default content
        previewPanel.clearPreview();
    }
    
    /**
     * Initializes basic window properties.
     */
    private void initializeWindow() {
        setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
        setMinimumSize(new Dimension(MIN_WIDTH, MIN_HEIGHT));
        setLocationRelativeTo(null);
    }
    
    /**
     * Creates all UI components.
     */
    private void createComponents() {
        searchPanel = new SearchPanel();
        resultsPanel = new ResultsPanel();
        previewPanel = new PreviewPanel();
        statusBarPanel = new StatusBarPanel();
        menuBarPanel = new MenuBarPanel();
    }
    
    /**
     * Layouts all components in the main window.
     */
    private void layoutComponents() {
        getContentPane().setLayout(new BorderLayout());
        
        // Top section: search and filters
        JPanel northPanel = new JPanel(new BorderLayout());
        northPanel.add(searchPanel, BorderLayout.CENTER);
        getContentPane().add(northPanel, BorderLayout.NORTH);
        
        // Center section: results and preview
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        splitPane.setResizeWeight(0.72); // Left panel gets 72% of space
        splitPane.add(resultsPanel);
        splitPane.add(previewPanel);
        getContentPane().add(splitPane, BorderLayout.CENTER);
        
        // Bottom section: status bar
        getContentPane().add(statusBarPanel, BorderLayout.SOUTH);
        
        // Menu bar
        setJMenuBar(menuBarPanel);
    }
    
    /**
     * Sets up event handlers for all components.
     */
    private void setupEventHandlers() {
        // Search panel events
        searchPanel.setSearchListener(this::handleSearch);
        
        // Results panel events
        resultsPanel.setResultsListener(new ResultsPanel.ResultsListener() {
            @Override
            public void onSelectionChanged(SearchResult result) {
                handleSelectionChange(result);
            }
            
            @Override
            public void onFileOpen(String path) {
                handleFileOpen(path);
            }
            
            @Override
            public void onFileReveal(String path) {
                handleFileReveal(path);
            }
        });
        
        // Menu bar events
        menuBarPanel.setMenuListener(new MenuBarPanel.MenuListener() {
            @Override
            public void onIndexFolder() {
                handleIndexFolder();
            }
            
            @Override
            public void onManageSources() {
                handleManageSources();
            }
            
            @Override
            public void onIndexAllSources() {
                handleIndexAllSources();
            }
            
            @Override
            public void onShowIndexingSettings() {
                handleShowIndexingSettings();
            }
            
            @Override
            public void onRebuildIndex() {
                handleRebuildIndex();
            }
            
            @Override
            public void onExportResults() {
                handleExportResults();
            }
            
            @Override
            public void onClearHistory() {
                handleClearHistory();
            }
            
            @Override
            public void onToggleLiveWatch() {
                handleToggleLiveWatch();
            }
            
            @Override
            public void onToggleNetworkPolling() {
                handleToggleNetworkPolling();
            }
            
            @Override
            public void onPollNow() {
                handlePollNow();
            }
            
            @Override
            public void onShowUsage() {
                handleShowUsage();
            }
            
            @Override
            public void onExit() {
                handleExit();
            }
        });
    }
    
    /**
     * Sets up application icons.
     */
    private void setupIcons() {
        setIconImages(IconUtil.loadAppImages());
        
        // Set taskbar/dock icon (largest one)
        List<java.awt.Image> images = IconUtil.loadAppImages();
        if (!images.isEmpty()) {
            IconUtil.setAppTaskbarIconIfSupported(images.get(images.size() - 1));
        }
    }
    
    /**
     * Handles search requests from the search panel.
     * 
     * @param query the search query
     * @param filters the filter state
     * @param scope the search scope
     * @param matchMode the match mode
     */
    private void handleSearch(String query, FilterState filters, SearchScope scope, MatchMode matchMode) {
        if (searchService == null) {
            statusBarPanel.setStatus("Search service unavailable.");
            return;
        }
        
        // Cancel any existing search
        if (activeSearchWorker != null) {
            activeSearchWorker.cancel(true);
        }
        
        // Handle empty query
        if (query.isEmpty()) {
            resultsPanel.clearResults();
            previewPanel.clearPreview();
            statusBarPanel.setStatus("Enter a query to search.");
            return;
        }
        
        // Start new search
        long token = searchSequence.incrementAndGet();
        statusBarPanel.setStatus("Searching...");
        previewPanel.clearPreview();
        
        SearchWorker worker = new SearchWorker(token, query, filters, scope, matchMode, searchService);
        worker.setSearchWorkerListener(new SearchWorker.SearchWorkerListener() {
            @Override
            public void onSearchCompleted(long token, String query, List<SearchResult> results, long elapsedMs) {
                handleSearchCompleted(token, query, results, elapsedMs);
            }
            
            @Override
            public void onSearchFailed(long token, String query, String errorMessage) {
                handleSearchFailed(token, query, errorMessage);
            }
        });
        
        activeSearchWorker = worker;
        worker.execute();
    }
    
    /**
     * Handles search completion.
     * 
     * @param token the search token
     * @param query the search query
     * @param results the search results
     * @param elapsedMs time elapsed in milliseconds
     */
    private void handleSearchCompleted(long token, String query, List<SearchResult> results, long elapsedMs) {
        if (token != searchSequence.get()) {
            return; // This search is outdated
        }
        
        activeSearchWorker = null;
        resultsPanel.setResults(results);
        
        if (results.isEmpty()) {
            statusBarPanel.setStatus(String.format("No results. | %d ms", elapsedMs));
            previewPanel.clearPreview();
        } else {
            statusBarPanel.setStatus(String.format("Results: %d  |  %d ms", results.size(), elapsedMs));
        }
        
        searchPanel.addToHistory(query);
    }
    
    /**
     * Handles search failure.
     * 
     * @param token the search token
     * @param query the search query
     * @param errorMessage the error message
     */
    private void handleSearchFailed(long token, String query, String errorMessage) {
        if (token != searchSequence.get()) {
            return; // This search is outdated
        }
        
        activeSearchWorker = null;
        statusBarPanel.setStatus("Search failed: " + errorMessage);
        previewPanel.clearPreview();
    }
    
    /**
     * Handles selection change in results table.
     * 
     * @param result the selected search result
     */
    private void handleSelectionChange(SearchResult result) {
        String query = searchPanel.getSearchField().getText().trim();
        previewPanel.setPreviewContent(result, query);
    }
    
    /**
     * Handles file open action.
     * 
     * @param path the file path to open
     */
    private void handleFileOpen(String path) {
        try {
            Desktop.getDesktop().open(new File(path));
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Open failed:\n" + ex.getMessage());
        }
    }
    
    /**
     * Handles file reveal action.
     * 
     * @param path the file path to reveal
     */
    private void handleFileReveal(String path) {
        try {
            if (isWindows()) {
                new ProcessBuilder("explorer.exe", "/select,", path).start();
            } else if (isMac()) {
                new ProcessBuilder("open", "-R", path).start();
            } else {
                // Linux: fallback to opening parent directory
                File f = new File(path);
                new ProcessBuilder("xdg-open", f.getParentFile().getAbsolutePath()).start();
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Reveal failed:\n" + ex.getMessage());
        }
    }
    
    /**
     * Handles index folder action.
     */
    private void handleIndexFolder() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Choose a folder to index");
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        
        File folder = fc.getSelectedFile();
        statusBarPanel.setStatus("Indexing: " + folder.getAbsolutePath() + " ...");
        
        Path indexDir = Paths.get(System.getProperty("user.home"), ".docfinder", "index");
        
        new SwingWorker<Integer, Void>() {
            @Override
            protected Integer doInBackground() throws Exception {
                ConfigManager cm = new ConfigManager();
                IndexSettings s = cm.loadIndexSettings();
                LuceneIndexer idx = new LuceneIndexer(indexDir, s);
                return idx.indexFolder(folder.toPath());
            }
            
            @Override
            protected void done() {
                try {
                    int n = get();
                    statusBarPanel.setStatus("Indexed files: " + n + "  |  Index: " + indexDir.toString());
                    setSearchService(new LuceneSearchService(indexDir));
                } catch (Exception ex) {
                    statusBarPanel.setStatus("Index failed: " + ex.getMessage());
                }
            }
        }.execute();
    }
    
    /**
     * Handles manage sources action.
     */
    private void handleManageSources() {
        new ManageSourcesDialog(this).setVisible(true);
        
        // Check if we need to restart watchers/pollers
        boolean needRestart = (liveWatchToggle != null && liveWatchToggle.isSelected())
                || (netPollToggle != null && netPollToggle.isSelected());
        if (!needRestart) {
            return;
        }
        
        int ans = JOptionPane.showConfirmDialog(this, 
                "Sources updated. Restart watchers/polling now?",
                "Apply Changes", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (ans != JOptionPane.OK_OPTION) {
            return;
        }
        
        // Restart background services
        restartBackgroundServices();
    }
    
    /**
     * Handles index all sources action.
     */
    private void handleIndexAllSources() {
        SourceManager sm = new SourceManager();
        List<Path> sources = sm.load();
        if (sources.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No sources configured. Use 'Index Sources...' first.");
            return;
        }
        
        long t0 = System.currentTimeMillis();
        statusBarPanel.setStatus("Indexing all sources...");
        
        Path indexDir = sm.getIndexDir();
        
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
                    long ms = System.currentTimeMillis() - t0;
                    statusBarPanel.setStatus("Indexed files: " + n + " | Time: " + ms + " ms | Index: " + indexDir);
                } catch (Exception ex) {
                    statusBarPanel.setStatus("Index failed: " + ex.getMessage());
                }
            }
        }.execute();
    }
    
    /**
     * Handles show indexing settings action.
     */
    private void handleShowIndexingSettings() {
        ConfigManager cm = new ConfigManager();
        IndexSettings s = cm.loadIndexSettings();
        
        JSpinner maxMb = new JSpinner(new SpinnerNumberModel((int) s.maxFileMB, 1, 1024, 1));
        JSpinner timeout = new JSpinner(new SpinnerNumberModel(s.parseTimeoutSec, 1, 120, 1));
        JTextField include = new JTextField(String.join(",", s.includeExt));
        JTextArea exclude = new JTextArea(String.join(";", s.excludeGlob));
        exclude.setRows(3);
        
        JPanel panel = new JPanel(new java.awt.GridBagLayout());
        java.awt.GridBagConstraints c = new java.awt.GridBagConstraints();
        c.insets = new java.awt.Insets(4, 4, 4, 4);
        c.fill = java.awt.GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        int row = 0;
        
        // Max file size
        c.gridx = 0; c.gridy = row;
        panel.add(new JLabel("Max file size (MB):"), c);
        c.gridx = 1;
        panel.add(maxMb, c);
        row++;
        
        // Parse timeout
        c.gridx = 0; c.gridy = row;
        panel.add(new JLabel("Parse timeout (sec):"), c);
        c.gridx = 1;
        panel.add(timeout, c);
        row++;
        
        // Include extensions
        c.gridx = 0; c.gridy = row;
        panel.add(new JLabel("Include extensions (comma):"), c);
        c.gridx = 1;
        panel.add(include, c);
        row++;
        
        // Exclude globs
        c.gridx = 0; c.gridy = row;
        panel.add(new JLabel("Exclude globs (semicolon):"), c);
        c.gridx = 1;
        panel.add(new JScrollPane(exclude), c);
        
        int ret = JOptionPane.showConfirmDialog(this, panel, "Indexing Settings", 
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (ret == JOptionPane.OK_OPTION) {
            s.maxFileMB = ((Number) maxMb.getValue()).longValue();
            s.parseTimeoutSec = ((Number) timeout.getValue()).intValue();
            s.includeExt = new java.util.ArrayList<>(FilterState.parseExts(include.getText()));
            s.excludeGlob = java.util.Arrays.asList(exclude.getText().split(";"));
            cm.saveIndexSettings(s);
            statusBarPanel.setStatus("Index settings saved.");
        }
    }
    
    /**
     * Handles rebuild index action.
     */
    private void handleRebuildIndex() {
        SourceManager sm = new SourceManager();
        List<Path> sources = sm.load();
        if (sources.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No sources configured. Use 'Index Sources...' first.");
            return;
        }
        
        ConfigManager cm = new ConfigManager();
        IndexSettings s = cm.loadIndexSettings();
        
        Path indexDir = sm.getIndexDir();
        statusBarPanel.setStatus("Rebuilding index (full)...");
        long t0 = System.currentTimeMillis();
        
        new SwingWorker<Integer, Void>() {
            @Override
            protected Integer doInBackground() throws Exception {
                LuceneIndexer idx = new LuceneIndexer(indexDir, s);
                return idx.indexFolders(sources, true); // full = true
            }
            
            @Override
            protected void done() {
                try {
                    int n = get();
                    long ms = System.currentTimeMillis() - t0;
                    statusBarPanel.setStatus("Rebuilt files: " + n + " | Time: " + ms + " ms | Index: " + indexDir);
                    setSearchService(new LuceneSearchService(indexDir));
                } catch (Exception ex) {
                    statusBarPanel.setStatus("Rebuild failed: " + ex.getMessage());
                }
            }
        }.execute();
    }
    
    /**
     * Handles export results action.
     */
    private void handleExportResults() {
        if (resultsPanel.getResultCount() == 0) {
            JOptionPane.showMessageDialog(this, "No rows to export.");
            return;
        }
        
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Export Results to CSV");
        fc.setSelectedFile(new File("docfinder-results.csv"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        
        File outFile = fc.getSelectedFile();
        exportToCsv(outFile, resultsPanel.getResultTable());
    }
    
    /**
     * Handles clear history action.
     */
    private void handleClearHistory() {
        int ret = JOptionPane.showConfirmDialog(this,
                "This will remove all saved search queries.\nProceed?", "Clear Search History",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (ret != JOptionPane.OK_OPTION) {
            return;
        }
        
        try {
            searchPanel.clearHistory();
            statusBarPanel.setStatus("Search history cleared.");
            previewPanel.clearPreview();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Failed to clear history:\n" + ex.getMessage(), 
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }
    
    /**
     * Handles toggle live watch action.
     */
    private void handleToggleLiveWatch() {
        // Implementation would be similar to original MainWindow
        // This is a placeholder - would need to integrate with menu items
        statusBarPanel.setStatus("Live watch toggle requested");
    }
    
    /**
     * Handles toggle network polling action.
     */
    private void handleToggleNetworkPolling() {
        // Implementation would be similar to original MainWindow
        // This is a placeholder - would need to integrate with menu items
        statusBarPanel.setStatus("Network polling toggle requested");
    }
    
    /**
     * Handles poll now action.
     */
    private void handlePollNow() {
        // Implementation would be similar to original MainWindow
        // This is a placeholder - would need to integrate with menu items
        statusBarPanel.setStatus("Poll now requested");
    }
    
    /**
     * Handles show usage action.
     */
    private void handleShowUsage() {
        String html = "<html><body style='width:640px;font-family:sans-serif;font-size:12px;line-height:1.5'>"
                + "<h2>DocFinder - Usage Guide</h2>"
                
                + "<h3>Overview</h3>"
                + "<ul>"
                + "<li>Fast file-name search (prefix boosted).</li>"
                + "<li>Content search via Apache Tika (read-only parsing).</li>"
                + "<li>Better CJK (Chinese/Japanese) matching with specialized analyzers.</li>"
                + "</ul>"
                
                + "<h3>Quick Start</h3>"
                + "<ol>"
                + "<li>Open <b>File → Index Sources…</b> to add folders.</li>"
                + "<li>Run <b>File → Index All Sources</b> to build/update the index, or <b>Rebuild Index (Full)</b> to recreate it from scratch.</li>"
                + "<li>Type your query and press <b>Enter</b>.</li>"
                + "</ol>"
                
                + "<h3>Query Examples</h3>"
                + "<ul>"
                + "<li><code>report*</code> — prefix match on file name</li>"
                + "<li><code>\"project plan\"</code> — phrase match</li>"
                + "<li><code>content:kubernetes AND ingress</code> — content-only query</li>"
                + "<li><code>name:\"设计\"</code> / <code>content:\"设计 概要\"</code> — Japanese examples</li>"
                + "</ul>"
                
                + "<h3>Filters</h3>"
                + "<ul>"
                + "<li>Click <b>Filters</b> to toggle filter bar.</li>"
                + "<li><b>Ext(s)</b>: comma-separated, e.g. <code>pdf,docx,txt</code>.</li>"
                + "<li><b>From / To</b>: date range (yyyy-MM-dd) for modified time.</li>"
                + "</ul>"
                
                + "<h3>Shortcuts & Actions</h3>"
                + "<ul>"
                + "<li><b>Ctrl+Alt+Space</b> — toggle main window</li>"
                + "<li><b>Enter</b> — run search / open selected file in results</li>"
                + "<li><b>Ctrl+C</b> — copy full path; <b>Ctrl+Shift+C</b> — copy file name</li>"
                + "<li><b>Alt+↓</b> — open query history dropdown</li>"
                + "<li><b>Ctrl+Shift+Delete</b> — Clear Search History</li>"
                + "<li>Right-click a result row: <i>Open / Reveal in Explorer / Copy</i></li>"
                + "</ul>"
                
                + "<h3>Privacy & Safety</h3>"
                + "<ul>"
                + "<li>Indexing opens files in <b>read-only</b> mode. Contents and mtime are never modified by DocFinder.</li>"
                + "<li>On some NAS/SMB systems, <i>atime</i> (last access time) may be updated by the server when files are read.</li>"
                + "</ul>"
                
                + "<h3>Notes</h3>"
                + "<ul>"
                + "<li>Very large or encrypted files may have empty previews.</li>"
                + "<li>OCR for scanned PDFs/images is optional (currently disabled by default).</li>"
                + "</ul>"
                + "</body></html>";
        
        JEditorPane ep = new JEditorPane("text/html", html);
        ep.setEditable(false);
        JScrollPane sp = new JScrollPane(ep);
        sp.setPreferredSize(new Dimension(680, 460));
        JOptionPane.showMessageDialog(this, sp, "Usage Guide", JOptionPane.PLAIN_MESSAGE);
    }
    
    /**
     * Handles exit action.
     */
    private void handleExit() {
        try {
            // Stop background services
            if (liveService != null) {
                liveService.close();
            }
            if (netPoller != null) {
                netPoller.close();
            }
            
            // Remove tray icon
            if (SystemTray.isSupported()) {
                SystemTray tray = SystemTray.getSystemTray();
                for (TrayIcon ti : tray.getTrayIcons()) {
                    tray.remove(ti);
                }
            }
        } catch (Exception ignore) {
            // Ignore cleanup errors
        }
        
        dispose();
        System.exit(0);
    }
    
    /**
     * Restarts background services (live watch and network polling).
     */
    private void restartBackgroundServices() {
        setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.WAIT_CURSOR));
        statusBarPanel.setStatus("Applying source changes...");
        
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() {
                try {
                    // Restart live watch if enabled
                    if (liveWatchToggle != null && liveWatchToggle.isSelected()) {
                        SwingUtilities.invokeLater(() -> {
                            liveWatchToggle.setSelected(false);
                            handleToggleLiveWatch();
                        });
                        Thread.sleep(200);
                        SwingUtilities.invokeLater(() -> {
                            liveWatchToggle.setSelected(true);
                            handleToggleLiveWatch();
                        });
                    }
                    
                    // Restart network polling if enabled
                    if (netPollToggle != null && netPollToggle.isSelected()) {
                        SwingUtilities.invokeLater(() -> {
                            netPollToggle.setSelected(false);
                            handleToggleNetworkPolling();
                        });
                        Thread.sleep(200);
                        SwingUtilities.invokeLater(() -> {
                            netPollToggle.setSelected(true);
                            handleToggleNetworkPolling();
                        });
                    }
                } catch (InterruptedException ignore) {
                    // Ignore interruption
                }
                return null;
            }
            
            @Override
            protected void done() {
                setCursor(java.awt.Cursor.getDefaultCursor());
                statusBarPanel.setStatus("Source changes applied.");
            }
        }.execute();
    }
    
    /**
     * Exports table data to CSV file.
     * 
     * @param outFile the output file
     * @param table the table to export
     */
    private void exportToCsv(File outFile, JTable table) {
        String sep = System.lineSeparator();
        
        try (java.io.PrintWriter pw = new java.io.PrintWriter(
                new java.io.OutputStreamWriter(new java.io.FileOutputStream(outFile), "UTF-8"))) {
            
            // Header
            int cols = table.getColumnCount();
            List<String> header = new java.util.ArrayList<>();
            for (int c = 0; c < cols; c++) {
                header.add(csvQuote(table.getColumnName(c)));
            }
            pw.write(String.join(",", header) + sep);
            
            // Data (in current sorted order)
            int rows = table.getRowCount();
            for (int r = 0; r < rows; r++) {
                List<String> cells = new java.util.ArrayList<>();
                for (int c = 0; c < cols; c++) {
                    Object val = table.getValueAt(r, c);
                    cells.add(csvQuote(val == null ? "" : val.toString()));
                }
                pw.write(String.join(",", cells) + sep);
            }
            pw.flush();
            statusBarPanel.setStatus("CSV exported: " + outFile.getAbsolutePath());
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Export failed:\n" + ex.getMessage(), 
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }
    
    /**
     * Quotes a string for CSV format.
     * 
     * @param s the string to quote
     * @return the quoted string
     */
    private static String csvQuote(String s) {
        String t = s.replace("\"", "\"\"");
        return "\"" + t + "\"";
    }
    
    /**
     * Checks if the current OS is Windows.
     * 
     * @return true if running on Windows
     */
    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }
    
    /**
     * Checks if the current OS is Mac.
     * 
     * @return true if running on Mac
     */
    private static boolean isMac() {
        return System.getProperty("os.name").toLowerCase().contains("mac");
    }
    
    /**
     * Sets the search service.
     * 
     * @param searchService the new search service
     */
    public void setSearchService(SearchService searchService) {
        this.searchService = searchService;
    }
    
    /**
     * Gets the search field for external access.
     * 
     * @return the search field
     */
    public JTextField getSearchField() {
        return searchPanel.getSearchField();
    }
    
    /**
     * Gets the results table for external access.
     * 
     * @return the results table
     */
    public JTable getResultTable() {
        return resultsPanel.getResultTable();
    }
    
    /**
     * Gets the status label for external access.
     * 
     * @return the status label
     */
    public JLabel getStatusLabel() {
        return statusBarPanel.getStatusLabel();
    }
    
    /**
     * Creates a tray icon image.
     * 
     * @return the tray icon image
     */
    public static Image createTrayImage() {
        int size = 16;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0x3B82F6));
        g.fillRoundRect(0, 0, size-1, size-1, 4, 4);
        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 9));
        g.drawString("DF", 2, 12);
        g.dispose();
        return img;
    }
}

