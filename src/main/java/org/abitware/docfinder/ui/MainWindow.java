package org.abitware.docfinder.ui;

import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.List;

import javax.swing.AbstractAction;
import javax.swing.ActionMap;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.InputMap;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JEditorPane;
import javax.swing.JFileChooser;
import javax.swing.JFormattedTextField;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SpinnerNumberModel;
import javax.swing.WindowConstants;
import javax.swing.table.DefaultTableModel;

import org.abitware.docfinder.search.SearchResult;
import org.abitware.docfinder.search.SearchService;
import org.abitware.docfinder.watch.NetPollerService.PollStats;

public class MainWindow extends JFrame {
	private org.abitware.docfinder.watch.LiveIndexService liveService;
	private javax.swing.JCheckBoxMenuItem liveWatchToggle;
	
	private javax.swing.JCheckBoxMenuItem netPollToggle;
	private org.abitware.docfinder.watch.NetPollerService netPoller;


    // ========= 字段 =========
    private SearchService searchService;

    // 顶部：搜索与过滤
    // 搜索框改为“可编辑下拉”，编辑器仍是 JTextField
    private final javax.swing.JComboBox<String> queryBox = new javax.swing.JComboBox<>();
    private javax.swing.JTextField searchField; // 实际的编辑器
    private final org.abitware.docfinder.search.SearchHistoryManager historyMgr =
            new org.abitware.docfinder.search.SearchHistoryManager();

    // Popup & “Open With” 记忆项（供右键菜单和刷新使用）
    private JPopupMenu rowPopup;
    private JMenuItem rememberedOpenWithItem;
    
    private final JTextField extField    = new JTextField(); // 逗号分隔扩展名
    private JFormattedTextField fromField; // yyyy-MM-dd
    private JFormattedTextField toField;   // yyyy-MM-dd
    private final JPanel filterBar = new JPanel(new BorderLayout(6, 6)); // 可折叠过滤条

    // 中部：结果 + 预览
    private final DefaultTableModel model = new DefaultTableModel(
    	    new Object[]{"Name", "Path", "Size", "Score", "Created", "Accessed", "Match"}, 0) {
    	    @Override public boolean isCellEditable(int r, int c) { return false; }
    	};

    private final JTable resultTable = new JTable(model);
    private final JEditorPane preview = new JEditorPane(
            "text/html",
            "<html><body style='font-family:sans-serif;font-size:11px;color:#333;line-height:1.4;padding:8px'>Preview</body></html>"
    );
    private JSplitPane split;

    // 底部：状态栏
    private final JLabel statusLabel = new JLabel("Ready");

    // 预览/搜索上下文
    private String lastQuery = "";


    // ========= 构造 =========
    public MainWindow(SearchService searchService) {
        super("DocFinder");
        this.searchService = searchService;

        setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
        setMinimumSize(new Dimension(900, 560));
        setLocationRelativeTo(null);
        getContentPane().setLayout(new BorderLayout());

        // 1) 顶部 North：搜索条 + 可折叠过滤条
        JPanel north = new JPanel(new BorderLayout());
        north.add(buildTopBar(), BorderLayout.NORTH);
        north.add(buildFilterBar(), BorderLayout.CENTER); // 默认隐藏
        getContentPane().add(north, BorderLayout.NORTH);

        // 2) 中部 Center：结果表 + 右侧预览
        getContentPane().add(buildCenterAndPreview(), BorderLayout.CENTER);

        // 3) 底部 South：状态栏
        getContentPane().add(buildStatusBar(), BorderLayout.SOUTH);

        // 4) 菜单栏（File / Help）
        setJMenuBar(buildMenuBar());

        // 5) 右键菜单、快捷键、行选择事件
        installTablePopupActions();  // 右键：Open / Reveal / Copy
        installTableShortcuts();     // Enter / Ctrl+C / Ctrl+Shift+C
        resultTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) loadPreviewAsync();
        });
        
        setIconImages(org.abitware.docfinder.ui.IconUtil.loadAppImages());

        // 进一步：设置 Taskbar/Dock 图标（挑最大的那张）
        java.util.List<java.awt.Image> imgs = org.abitware.docfinder.ui.IconUtil.loadAppImages();
        if (!imgs.isEmpty()) {
         java.awt.Image best = imgs.get(imgs.size() - 1);
         org.abitware.docfinder.ui.IconUtil.setAppTaskbarIconIfSupported(best);
        }
    }

    /** 顶部搜索条（含 Filters 按钮） */
    private JComponent buildTopBar() {
        JPanel top = new JPanel(new BorderLayout(8, 8));
        top.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));

        // 可编辑下拉
        queryBox.setEditable(true);
        queryBox.setToolTipText("Tips: name:<term>, content:<term>, phrase with quotes, AND/OR, wildcard *");

        // 取到 editor 的 JTextField 以便设置 placeholder 和监听回车
        searchField = (javax.swing.JTextField) queryBox.getEditor().getEditorComponent();
        searchField.putClientProperty("JTextField.placeholderText",
                "Search…  (e.g. report*, content:\"zero knowledge\", name:\"設計\")");
        // 回车触发搜索
        searchField.addActionListener(e -> doSearch());

        // 下拉选择某条历史时也触发搜索
        queryBox.addActionListener(e -> {
            Object sel = queryBox.getSelectedItem();
            if (sel != null && queryBox.isPopupVisible()) {
                setQueryText(sel.toString());
                doSearch();
            }
        });

        // 初次加载历史
        java.util.List<String> hist = historyMgr.load();
        for (String s : hist) queryBox.addItem(s);
        
        // ✅ 关键：保持编辑器为空，placeholder 才会显示
        queryBox.setSelectedItem("");           // <-- 新增
        searchField.requestFocusInWindow();     // 可选：把输入焦点放到编辑器

        JButton toggleFilters = new JButton("Filters");
        toggleFilters.addActionListener(e -> filterBar.setVisible(!filterBar.isVisible()));

        top.add(new JLabel("🔎"), BorderLayout.WEST);
        top.add(queryBox, BorderLayout.CENTER);
        top.add(toggleFilters, BorderLayout.EAST);
        return top;
    }

    private String getQueryText() {
        return (searchField == null) ? "" : searchField.getText().trim();
    }
    private void setQueryText(String s) {
        if (searchField != null) searchField.setText(s);
    }


    /** 过滤条（扩展名 + 时间范围），默认隐藏 */
    private JComponent buildFilterBar() {
        // 日期格式器
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd");
        fromField = new JFormattedTextField(sdf); fromField.setColumns(10);
        toField   = new JFormattedTextField(sdf); toField.setColumns(10);

        JPanel row = new JPanel();
        row.setLayout(new FlowLayout(FlowLayout.LEFT, 8, 4));

        row.add(new JLabel("Ext(s):"));
        extField.setColumns(16);
        extField.setToolTipText("Comma-separated, e.g. pdf,docx,txt");
        row.add(extField);

        row.add(new JLabel("From:"));
        row.add(fromField);
        row.add(new JLabel("To:"));
        row.add(toField);

        JButton applyBtn = new JButton("Apply");
        applyBtn.addActionListener(e -> { applyFilters(); doSearch(); });
        row.add(applyBtn);

        filterBar.add(row, BorderLayout.CENTER);
        filterBar.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        filterBar.setVisible(false); // 默认折叠
        return filterBar;
    }

    /** 中心区域：结果表 + 预览面板（分栏） */
    private JComponent buildCenterAndPreview() {
        // 结果表基础设置与列宽
        resultTable.setFillsViewportHeight(true);
        resultTable.setRowHeight(22);
        resultTable.setAutoCreateRowSorter(true);
        
        resultTable.getColumnModel().getColumn(0).setPreferredWidth(240); // Name
        resultTable.getColumnModel().getColumn(1).setPreferredWidth(480); // Path
        resultTable.getColumnModel().getColumn(2).setPreferredWidth(90);  // Size ✅
        resultTable.getColumnModel().getColumn(3).setPreferredWidth(70);  // Score
        resultTable.getColumnModel().getColumn(4).setPreferredWidth(130); // Created
        resultTable.getColumnModel().getColumn(5).setPreferredWidth(130); // Accessed
        resultTable.getColumnModel().getColumn(6).setPreferredWidth(110); // Match

        JScrollPane center = new JScrollPane(resultTable);

        // 预览：只读 HTML，字体 11px（小一点）
        preview.setEditable(false);
        JScrollPane right = new JScrollPane(preview);
        right.setPreferredSize(new Dimension(360, 560));

        split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, center, right);
        split.setResizeWeight(0.72); // 左侧主列表占比分配
        return split;
    }

    /** 底部状态栏 */
    private JComponent buildStatusBar() {
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        bottom.add(statusLabel, BorderLayout.WEST);
        return bottom;
    }

    /** 菜单栏（File / Help） */
    private JMenuBar buildMenuBar() {
        JMenuBar bar = new JMenuBar();

        JMenu file = new JMenu("File");

        JMenuItem indexItem = new JMenuItem("Index Folder...");
        indexItem.addActionListener(e -> chooseAndIndexFolder());
        file.add(indexItem);

        JMenuItem sourcesItem = new JMenuItem("Index Sources...");
        sourcesItem.addActionListener(e -> manageSources());
        file.add(sourcesItem);

        JMenuItem indexAllItem = new JMenuItem("Index All Sources");
        indexAllItem.addActionListener(e -> indexAllSources());
        file.add(indexAllItem);

        JMenuItem idxSettings = new JMenuItem("Indexing Settings...");
        idxSettings.addActionListener(e -> showIndexingSettings());
        file.add(idxSettings);
        
        JMenuItem rebuildItem = new JMenuItem("Rebuild Index (Full)");
        rebuildItem.addActionListener(e -> rebuildAllSources());
        file.add(rebuildItem);

        file.addSeparator();
        
        JMenuItem exportCsv = new JMenuItem("Export Results to CSV...");
        exportCsv.addActionListener(e -> exportResultsToCsv());
        file.add(exportCsv);

        file.addSeparator();
        
        JMenuItem clearHist = new JMenuItem("Clear Search History...");
        clearHist.setToolTipText("Remove all saved queries (keeps the index intact)");
        // Java 8 的快捷键写法：MenuShortcutKeyMask() + SHIFT_MASK
        clearHist.setAccelerator(KeyStroke.getKeyStroke(
                java.awt.event.KeyEvent.VK_DELETE,
                java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMask() | java.awt.event.InputEvent.SHIFT_MASK
        ));
        clearHist.addActionListener(e -> clearSearchHistory());
        file.add(clearHist);
        
        file.addSeparator();
        liveWatchToggle = new JCheckBoxMenuItem("Enable Live Watch (Local)");
        liveWatchToggle.addActionListener(e -> toggleLiveWatch());
        file.add(liveWatchToggle);
        
        netPollToggle = new JCheckBoxMenuItem("Enable Network Polling");
        netPollToggle.addActionListener(e -> toggleNetPolling());
        file.add(netPollToggle);

        JMenuItem pollNow = new JMenuItem("Poll Now");
        pollNow.addActionListener(e -> pollOnceNow());
        file.add(pollNow);

        bar.add(file);

        JMenu help = new JMenu("Help");
        JMenuItem usage = new JMenuItem("Usage Guide");
        usage.addActionListener(e -> showUsageDialog());
        JMenuItem about = new JMenuItem("About DocFinder");
        about.addActionListener(e -> JOptionPane.showMessageDialog(this,
                "DocFinder\n\nLocal file name & content search.\n- Read-only indexing\n- Lucene + Tika\n- Java 8+\n",
                "About", JOptionPane.INFORMATION_MESSAGE));
        help.add(usage);
        help.add(about);
        bar.add(help);

        return bar;
    }
    
    private void toggleNetPolling() {
        try {
            org.abitware.docfinder.index.SourceManager sm = new org.abitware.docfinder.index.SourceManager();
            java.util.List<org.abitware.docfinder.index.SourceManager.SourceEntry> entries = sm.loadEntries();
            java.util.List<java.nio.file.Path> netRoots = new java.util.ArrayList<>();
            for (org.abitware.docfinder.index.SourceManager.SourceEntry e : entries) {
                if (e.network) netRoots.add(java.nio.file.Paths.get(e.path));
            }
            if (netRoots.isEmpty()) {
                JOptionPane.showMessageDialog(this, "No network sources.");
                netPollToggle.setSelected(false);
                return;
            }

            org.abitware.docfinder.index.ConfigManager cm = new org.abitware.docfinder.index.ConfigManager();
            int minutes = cm.getPollingMinutes();
            org.abitware.docfinder.index.IndexSettings s = cm.loadIndexSettings();

            if (netPollToggle.isSelected()) {
                netPoller = new org.abitware.docfinder.watch.NetPollerService(sm.getIndexDir(), s, netRoots);
                netPoller.start(minutes);
                cm.setPollingEnabled(true);
                statusLabel.setText("Network polling: ON (every " + minutes + " min)");
            } else {
                if (netPoller != null) { netPoller.close(); netPoller = null; }
                cm.setPollingEnabled(false);
                statusLabel.setText("Network polling: OFF");
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Network polling failed:\n" + ex.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
            netPollToggle.setSelected(false);
        }
    }

    private void pollOnceNow() {
        org.abitware.docfinder.index.SourceManager sm = new org.abitware.docfinder.index.SourceManager();
        java.util.List<org.abitware.docfinder.index.SourceManager.SourceEntry> entries = sm.loadEntries();
        java.util.List<java.nio.file.Path> netRoots = new java.util.ArrayList<>();
        for (org.abitware.docfinder.index.SourceManager.SourceEntry e : entries) {
            if (e.network) netRoots.add(java.nio.file.Paths.get(e.path));
        }
        if (netRoots.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No network sources.");
            return;
        }

        org.abitware.docfinder.index.ConfigManager cm = new org.abitware.docfinder.index.ConfigManager();
        org.abitware.docfinder.index.IndexSettings s = cm.loadIndexSettings();

        boolean ephemeral = (netPoller == null);
        if (ephemeral) {
            netPoller = new org.abitware.docfinder.watch.NetPollerService(sm.getIndexDir(), s, netRoots);
        }

        setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.WAIT_CURSOR));
        statusLabel.setText("Polling network sources…");

        new javax.swing.SwingWorker<org.abitware.docfinder.watch.NetPollerService.PollStats, Void>() {
            @Override protected org.abitware.docfinder.watch.NetPollerService.PollStats doInBackground() throws Exception {
                return netPoller.pollNowAsync().get();
            }
            @Override protected void done() {
                try {
                    PollStats st = get();
                    statusLabel.setText(String.format(
                            "Polled: scanned=%d, created=%d, modified=%d, deleted=%d | %d ms",
                            st.scannedFiles, st.created, st.modified, st.deleted, st.durationMs));
                    if (lastQuery != null && !lastQuery.trim().isEmpty()) doSearch();
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(MainWindow.this, "Poll failed:\n" + ex.getMessage(),
                            "Error", JOptionPane.ERROR_MESSAGE);
                } finally {
                    setCursor(java.awt.Cursor.getDefaultCursor());
                    if (ephemeral) { netPoller.close(); netPoller = null; }
                }
            }
        }.execute();
    }


    private void toggleLiveWatch() {
        try {
            org.abitware.docfinder.index.SourceManager sm = new org.abitware.docfinder.index.SourceManager();
            java.util.List<org.abitware.docfinder.index.SourceManager.SourceEntry> entries = sm.loadEntries();
            java.util.List<java.nio.file.Path> localRoots = new java.util.ArrayList<>();
            for (org.abitware.docfinder.index.SourceManager.SourceEntry e : entries) {
                if (!e.network) localRoots.add(java.nio.file.Paths.get(e.path));
            }
            if (localRoots.isEmpty()) {
                JOptionPane.showMessageDialog(this, "No local sources. Use 'Index Sources...' first.");
                liveWatchToggle.setSelected(false);
                return;
            }
            org.abitware.docfinder.index.ConfigManager cm = new org.abitware.docfinder.index.ConfigManager();
            org.abitware.docfinder.index.IndexSettings s = cm.loadIndexSettings();

            if (liveWatchToggle.isSelected()) {
                liveService = new org.abitware.docfinder.watch.LiveIndexService(sm.getIndexDir(), s, localRoots);
                liveService.start();
                statusLabel.setText("Live watch: ON (" + localRoots.size() + " local root(s))");
            } else {
                if (liveService != null) { liveService.close(); liveService = null; }
                statusLabel.setText("Live watch: OFF");
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Live watch failed:\n" + ex.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
            liveWatchToggle.setSelected(false);
        }
    }
    
    /** 清空搜索历史：确认 -> 清空持久化文件内容 -> 清空下拉列表 -> 清空输入框 */
    private void clearSearchHistory() {
        int ret = javax.swing.JOptionPane.showConfirmDialog(
                this,
                "This will remove all saved search queries.\nProceed?",
                "Clear Search History",
                javax.swing.JOptionPane.OK_CANCEL_OPTION,
                javax.swing.JOptionPane.WARNING_MESSAGE
        );
        if (ret != javax.swing.JOptionPane.OK_OPTION) return;

        try {
            // 1) 清空持久化历史
            historyMgr.save(java.util.Collections.emptyList());

            // 2) 清空下拉模型
            javax.swing.DefaultComboBoxModel<String> m =
                    (javax.swing.DefaultComboBoxModel<String>) queryBox.getModel();
            m.removeAllElements();

            // 3) 清空当前输入
            setQueryText("");

            // 4) 状态提示
            statusLabel.setText("Search history cleared.");
            // 预览区给个轻量提示
            preview.setText(htmlWrap("Search history cleared."));
        } catch (Exception ex) {
            javax.swing.JOptionPane.showMessageDialog(this,
                    "Failed to clear history:\n" + ex.getMessage(),
                    "Error", javax.swing.JOptionPane.ERROR_MESSAGE);
        }
    }


    /** 结果表快捷键：Enter 打开、Ctrl+C 复制路径、Ctrl+Shift+C 复制名称 */
    private void installTableShortcuts() {
        InputMap im = resultTable.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        ActionMap am = resultTable.getActionMap();

        im.put(KeyStroke.getKeyStroke("ENTER"), "open");
        im.put(KeyStroke.getKeyStroke("ctrl C"), "copyPath");
        im.put(KeyStroke.getKeyStroke("ctrl shift C"), "copyName");

        am.put("open", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                RowSel s = getSelectedRow();
                if (s != null) {
                	String p = org.abitware.docfinder.util.Utils.toExplorerFriendlyPath(s.path);
                    try { Desktop.getDesktop().open(new java.io.File(p)); } catch (Exception ignore) {}
                }
            }
        });
        am.put("copyPath", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                RowSel s = getSelectedRow();
                if (s != null) setClipboard(s.path);
            }
        });
        am.put("copyName", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                RowSel s = getSelectedRow();
                if (s != null) setClipboard(s.name);
            }
        });
    }
    
    private void manageSources() {
        // 1) 打开对话框（modal，用户编辑/保存源列表）
        new org.abitware.docfinder.ui.ManageSourcesDialog(this).setVisible(true);

        // 2) 询问是否重启 Live Watch / Poller（避免必卡）
        boolean needRestart = (liveWatchToggle != null && liveWatchToggle.isSelected())
                           || (netPollToggle  != null && netPollToggle.isSelected());
        if (!needRestart) return;

        int ans = javax.swing.JOptionPane.showConfirmDialog(
                this,
                "Sources updated. Restart watchers/polling now?",
                "Apply Changes",
                javax.swing.JOptionPane.OK_CANCEL_OPTION,
                javax.swing.JOptionPane.QUESTION_MESSAGE);
        if (ans != javax.swing.JOptionPane.OK_OPTION) return;

        // 3) 后台重启（避免阻塞 UI）
        setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.WAIT_CURSOR));
        statusLabel.setText("Applying source changes…");

        new javax.swing.SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() {
                try {
                    if (liveWatchToggle != null && liveWatchToggle.isSelected()) {
                        // 停-启
                        javax.swing.SwingUtilities.invokeLater(() -> {
                            liveWatchToggle.setSelected(false);
                            toggleLiveWatch();
                        });
                        // 等待 toggle 完毕（简单 sleep，避免在 EDT 中阻塞）
                        Thread.sleep(200);
                        javax.swing.SwingUtilities.invokeLater(() -> {
                            liveWatchToggle.setSelected(true);
                            toggleLiveWatch();
                        });
                    }
                    if (netPollToggle != null && netPollToggle.isSelected()) {
                        javax.swing.SwingUtilities.invokeLater(() -> {
                            netPollToggle.setSelected(false);
                            toggleNetPolling();
                        });
                        Thread.sleep(200);
                        javax.swing.SwingUtilities.invokeLater(() -> {
                            netPollToggle.setSelected(true);
                            toggleNetPolling();
                        });
                    }
                } catch (InterruptedException ignore) {}
                return null;
            }
            @Override protected void done() {
                setCursor(java.awt.Cursor.getDefaultCursor());
                statusLabel.setText("Source changes applied.");
            }
        }.execute();
    }

    private void indexAllSources() {
        org.abitware.docfinder.index.SourceManager sm = new org.abitware.docfinder.index.SourceManager();
        java.util.List<java.nio.file.Path> sources = sm.load();
        if (sources.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No sources configured. Use 'Index Sources...' first.");
            return;
        }
        
        long t0 = System.currentTimeMillis();
        statusLabel.setText("Indexing all sources…");

        java.nio.file.Path indexDir = sm.getIndexDir();

        new javax.swing.SwingWorker<Integer, Void>() {
            @Override protected Integer doInBackground() throws Exception {
            	org.abitware.docfinder.index.ConfigManager cm = new org.abitware.docfinder.index.ConfigManager();
            	org.abitware.docfinder.index.IndexSettings s = cm.loadIndexSettings();
            	org.abitware.docfinder.index.LuceneIndexer idx =
            	        new org.abitware.docfinder.index.LuceneIndexer(indexDir, s);

                int total = 0;
                for (java.nio.file.Path p : sources) {
                    total += idx.indexFolder(p);
                }
                return total;
            }
            @Override protected void done() {
                try {
                    int n = get();
                    long ms = System.currentTimeMillis() - t0;
                    statusLabel.setText("Indexed files: " + n + " | Time: " + ms + " ms | Index: " + indexDir);
                } catch (Exception ex) {
                    statusLabel.setText("Index failed: " + ex.getMessage());
                }
            }
        }.execute();
    }

    
    // 用 Tika 只读抽取的轻量预览（在 EDT 之外跑）
    private void loadPreviewAsync() {
        RowSel s = getSelectedRow();
        if (s == null) { preview.setText(htmlWrap("No selection.")); return; }
        preview.setText(htmlWrap("Loading preview…"));
        new javax.swing.SwingWorker<String, Void>() {
            @Override protected String doInBackground() throws Exception {
                // 1) 只读打开并抽取前 N 字符
                final int MAX_CHARS = 60_000; // 预览上限，不保存到索引
                String text = extractTextHead(java.nio.file.Paths.get(s.path), MAX_CHARS);
                if (text == null || text.isEmpty()) return htmlWrap("(No text content.)");

                // 2) 根据查询词做一个非常简单的高亮，找第一处命中，取上下文
                String q = (lastQuery == null) ? "" : lastQuery.trim();
                String[] terms = tokenizeForHighlight(q);
                String snippet = makeSnippet(text, terms, 300); // 取约 300 字符上下文
                String html = toHtml(snippet, terms);
                return htmlWrap(html);
            }
            @Override protected void done() {
                try { preview.setText(get()); preview.setCaretPosition(0); }
                catch (Exception ex) { preview.setText(htmlWrap("Preview failed.")); }
            }
        }.execute();
    }

    // 只读抽取前 N 字符（复用我们已有的 Tika 逻辑，简化为局部方法以免循环依赖）
    private String extractTextHead(java.nio.file.Path file, int maxChars) {
        try (java.io.InputStream is = java.nio.file.Files.newInputStream(file, java.nio.file.StandardOpenOption.READ)) {
            org.apache.tika.metadata.Metadata md = new org.apache.tika.metadata.Metadata();
            md.set(org.apache.tika.metadata.TikaCoreProperties.RESOURCE_NAME_KEY, file.getFileName().toString());
            org.apache.tika.parser.AutoDetectParser parser = new org.apache.tika.parser.AutoDetectParser();
            org.apache.tika.sax.BodyContentHandler handler = new org.apache.tika.sax.BodyContentHandler(maxChars);
            org.apache.tika.parser.ParseContext ctx = new org.apache.tika.parser.ParseContext();
            parser.parse(is, handler, md, ctx);
            return handler.toString();
        } catch (Throwable e) {
            return "";
        }
    }

    // 从查询串里提取要高亮的词（非常简化：去掉字段前缀/引号/AND/OR）
    private String[] tokenizeForHighlight(String q) {
        if (q == null) return new String[0];
        q = q.replaceAll("(?i)\\b(name|content|path):", " "); // 去字段前缀
        q = q.replace("\"", " ").replace("'", " ");
        q = q.replaceAll("(?i)\\bAND\\b|\\bOR\\b|\\bNOT\\b", " ");
        q = q.trim();
        if (q.isEmpty()) return new String[0];
        // 分词：按空白切；中日文情况下直接保留整段词
        String[] arr = q.split("\\s+");
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        for (String t : arr) {
            t = t.trim();
            if (t.isEmpty() || "*".equals(t)) continue;
            set.add(t);
        }
        return set.toArray(new String[0]);
    }

    // 生成包含第一个命中的简短片段（上下文 window）
    private String makeSnippet(String text, String[] terms, int window) {
        if (terms.length == 0) return text.substring(0, Math.min(window, text.length()));
        String lower = text.toLowerCase();
        int pos = -1;
        for (String t : terms) {
            int p = lower.indexOf(t.toLowerCase());
            if (p >= 0 && (pos == -1 || p < pos)) pos = p;
        }
        if (pos == -1) return text.substring(0, Math.min(window, text.length()));
        int start = Math.max(0, pos - window/2);
        int end   = Math.min(text.length(), start + window);
        return text.substring(start, end);
    }

    // 将片段转成简单 HTML 并高亮 <mark>
    private String toHtml(String snippet, String[] terms) {
        String esc = snippet.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");
        for (String t : terms) {
            if (t.isEmpty()) continue;
            try {
                esc = esc.replaceAll("(?i)" + java.util.regex.Pattern.quote(t),
                        "<mark>$0</mark>");
            } catch (Exception ignore) {}
        }
        return esc.replace("\n", "<br/>");
    }
    
    private String htmlWrap(String inner) {
        return "<html><body style='font-family:sans-serif;font-size:11px;color:#333;line-height:1.4;padding:8px'>"
                + inner + "</body></html>";
    }

    private RowSel getSelectedRow() {
        int row = resultTable.getSelectedRow();
        if (row < 0) return null;
        String name = String.valueOf(resultTable.getValueAt(row, 0));
        String path = String.valueOf(resultTable.getValueAt(row, 1));
        return new RowSel(name, path);
    }

    private void chooseAndIndexFolder() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Choose a folder to index");
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;

        java.io.File folder = fc.getSelectedFile();
        statusLabel.setText("Indexing: " + folder.getAbsolutePath() + " ...");

        // 索引目录：用户主目录下 .docfinder/index
        java.nio.file.Path indexDir = java.nio.file.Paths.get(
                System.getProperty("user.home"), ".docfinder", "index");

        new javax.swing.SwingWorker<Integer, Void>() {
            @Override protected Integer doInBackground() throws Exception {
            	org.abitware.docfinder.index.ConfigManager cm = new org.abitware.docfinder.index.ConfigManager();
            	org.abitware.docfinder.index.IndexSettings s = cm.loadIndexSettings();
            	org.abitware.docfinder.index.LuceneIndexer idx =
            	        new org.abitware.docfinder.index.LuceneIndexer(indexDir, s);

                return idx.indexFolder(folder.toPath());
            }
            @Override protected void done() {
                try {
                    int n = get();
                    statusLabel.setText("Indexed files: " + n + "  |  Index: " + indexDir.toString());
                    // 切换到 Lucene 搜索服务
                    setSearchService(new org.abitware.docfinder.search.LuceneSearchService(indexDir));
                    // 可选：自动触发一次搜索以验证
                    // doSearch();
                } catch (Exception ex) {
                    statusLabel.setText("Index failed: " + ex.getMessage());
                }
            }
        }.execute();
    }

    private void showIndexingSettings() {
        org.abitware.docfinder.index.ConfigManager cm = new org.abitware.docfinder.index.ConfigManager();
        org.abitware.docfinder.index.IndexSettings s = cm.loadIndexSettings();

        JSpinner maxMb = new JSpinner(new SpinnerNumberModel((int)s.maxFileMB, 1, 1024, 1));
        JSpinner timeout = new JSpinner(new SpinnerNumberModel(s.parseTimeoutSec, 1, 120, 1));
        JTextField include = new JTextField(String.join(",", s.includeExt));
        JTextArea exclude  = new JTextArea(String.join(";", s.excludeGlob));
        exclude.setRows(3);

        JPanel p = new JPanel(new java.awt.GridBagLayout());
        java.awt.GridBagConstraints c = new java.awt.GridBagConstraints();
        c.insets = new java.awt.Insets(4,4,4,4); c.fill = java.awt.GridBagConstraints.HORIZONTAL; c.weightx=1;
        int r=0;
        c.gridx=0;c.gridy=r; p.add(new JLabel("Max file size (MB):"), c);
        c.gridx=1; p.add(maxMb, c); r++;
        c.gridx=0;c.gridy=r; p.add(new JLabel("Parse timeout (sec):"), c);
        c.gridx=1; p.add(timeout, c); r++;
        c.gridx=0;c.gridy=r; p.add(new JLabel("Include extensions (comma):"), c);
        c.gridx=1; p.add(include, c); r++;
        c.gridx=0;c.gridy=r; p.add(new JLabel("Exclude globs (semicolon):"), c);
        c.gridx=1; p.add(new JScrollPane(exclude), c);

        int ret = JOptionPane.showConfirmDialog(this, p, "Indexing Settings",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (ret == JOptionPane.OK_OPTION) {
            s.maxFileMB = ((Number)maxMb.getValue()).longValue();
            s.parseTimeoutSec = ((Number)timeout.getValue()).intValue();
            s.includeExt = new java.util.ArrayList<>(
                    org.abitware.docfinder.search.FilterState.parseExts(include.getText())
            );
            s.excludeGlob = java.util.Arrays.asList(exclude.getText().split(";"));
            cm.saveIndexSettings(s);
            statusLabel.setText("Index settings saved.");
        }
    }

    /** 强制全量重建索引（CREATE 模式） */
    private void rebuildAllSources() {
        org.abitware.docfinder.index.SourceManager sm = new org.abitware.docfinder.index.SourceManager();
        java.util.List<java.nio.file.Path> sources = sm.load();
        if (sources.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No sources configured. Use 'Index Sources...' first.");
            return;
        }
        org.abitware.docfinder.index.ConfigManager cm = new org.abitware.docfinder.index.ConfigManager();
        org.abitware.docfinder.index.IndexSettings s = cm.loadIndexSettings();

        java.nio.file.Path indexDir = sm.getIndexDir();
        statusLabel.setText("Rebuilding index (full)…");
        long t0 = System.currentTimeMillis();

        new javax.swing.SwingWorker<Integer, Void>() {
            @Override protected Integer doInBackground() throws Exception {
                org.abitware.docfinder.index.LuceneIndexer idx =
                        new org.abitware.docfinder.index.LuceneIndexer(indexDir, s);
                return idx.indexFolders(sources, true); // ✅ full = true
            }
            @Override protected void done() {
                try {
                    int n = get();
                    long ms = System.currentTimeMillis() - t0;
                    statusLabel.setText("Rebuilt files: " + n + " | Time: " + ms + " ms | Index: " + indexDir);
                    setSearchService(new org.abitware.docfinder.search.LuceneSearchService(indexDir));
                } catch (Exception ex) {
                    statusLabel.setText("Rebuild failed: " + ex.getMessage());
                }
            }
        }.execute();
    }
    
    private void doSearch() {
    	applyFilters(); // 确保每次搜索前同步当前过滤器

        String q = searchField.getText().trim();
        lastQuery = q;  // 保存本次查询词，用于预览高亮
        
        long t0 = System.currentTimeMillis();
        List<SearchResult> list = searchService.search(q, 100);
        
        model.setRowCount(0);
        for (SearchResult r : list) {
            model.addRow(new Object[]{
                r.name,
                r.path,
                fmtSize(r.sizeBytes),               // ✅ 新增 Size 列
                String.format("%.3f", r.score),
                fmtTime(r.ctime),
                fmtTime(r.atime),
                (r.match == null ? "" : r.match)
            });
        }

        long ms = System.currentTimeMillis() - t0;
        if (list.isEmpty()) {
        	statusLabel.setText(String.format("No results. | %d ms", ms));
        	preview.setText(htmlWrap("No results.")); // 右侧预览区也给个提示
        } else {
        	statusLabel.setText(String.format("Results: %d  |  %d ms", list.size(), ms));
        }
        
        addToHistory(q);
        
        statusLabel.setText(String.format("Results: %d  |  %d ms", list.size(), ms));
    }
    
    private void addToHistory(String q) {
        q = (q == null) ? "" : q.trim();
        if (q.isEmpty()) return;

        java.util.List<String> latest = historyMgr.addAndSave(q);

        // 更新下拉模型：去重置顶、最多100
        javax.swing.DefaultComboBoxModel<String> m = (javax.swing.DefaultComboBoxModel<String>) queryBox.getModel();
        // 简单粗暴：清空重加（100 项以内性能无感）
        m.removeAllElements();
        for (String s : latest) m.addElement(s);
        queryBox.setSelectedItem(q); // 置顶显示
    }


    // 新增方法：
    private void installTablePopupActions() {
        rowPopup = new JPopupMenu();

        JMenuItem openItem   = new JMenuItem("Open");
        JMenuItem revealItem = new JMenuItem("Reveal in Explorer");
        JMenu copyMenu       = new JMenu("Copy");
        JMenuItem copyName   = new JMenuItem("Name");
        JMenuItem copyPath   = new JMenuItem("Full Path");
        copyMenu.add(copyName); copyMenu.add(copyPath);

        // --- Open With… 子菜单 ---
        JMenu openWith = new JMenu("Open With…");
        JMenuItem chooseProg = new JMenuItem("Choose Program…");
        openWith.add(chooseProg);
        openWith.addSeparator();
        rememberedOpenWithItem = new JMenuItem("(remembered)"); // 用类字段保存
        rememberedOpenWithItem.setVisible(false);
        openWith.add(rememberedOpenWithItem);

        // 组装菜单
        rowPopup.add(openItem);
        rowPopup.add(openWith);
        rowPopup.add(revealItem);
        rowPopup.addSeparator();
        rowPopup.add(copyMenu);

        // 选择程序并记住
        chooseProg.addActionListener(e -> {
            RowSel s = getSelectedRow(); if (s == null) return;
            javax.swing.JFileChooser fc = new javax.swing.JFileChooser();
            fc.setDialogTitle("Choose a program to open this file");
            fc.setFileSelectionMode(javax.swing.JFileChooser.FILES_ONLY);
            if (fc.showOpenDialog(this) == javax.swing.JFileChooser.APPROVE_OPTION) {
                java.io.File prog = fc.getSelectedFile();
                String ext = getExtFromName(s.name);
                org.abitware.docfinder.index.ConfigManager cm = new org.abitware.docfinder.index.ConfigManager();
                cm.setOpenWithProgram(ext, prog.getAbsolutePath());
                openWithProgram(prog.getAbsolutePath(), s.path);
            }
        });

        // 其余动作（Open/Reveal/Copy）保持你之前的实现...
        openItem.addActionListener(e -> {
            RowSel s = getSelectedRow(); if (s == null) return;
            try { java.awt.Desktop.getDesktop().open(new java.io.File(s.path)); }
            catch (Exception ex) { JOptionPane.showMessageDialog(this, "Open failed:\n" + ex.getMessage()); }
        });
        revealItem.addActionListener(e -> {
            RowSel s = getSelectedRow(); if (s == null) return;
            try { revealInExplorer(s.path); } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Reveal failed:\n" + ex.getMessage());
            }
        });
        copyName.addActionListener(e -> { RowSel s = getSelectedRow(); if (s != null) setClipboard(s.name); });
        copyPath.addActionListener(e -> { RowSel s = getSelectedRow(); if (s != null) setClipboard(s.path); });

        // 右键触发：按下/弹起都判断
        resultTable.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mousePressed(java.awt.event.MouseEvent e)  { showPopup(e); }
            @Override public void mouseReleased(java.awt.event.MouseEvent e) { showPopup(e); }
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2 && e.getButton() == java.awt.event.MouseEvent.BUTTON1) {
                    RowSel s = getSelectedRow(); if (s == null) return;
                    try { java.awt.Desktop.getDesktop().open(new java.io.File(s.path)); } catch (Exception ignore) {}
                }
            }
        });
    }

    /** 右键菜单弹出，并动态刷新“记忆的程序”项 */
    private void showPopup(java.awt.event.MouseEvent e) {
        int r = resultTable.rowAtPoint(e.getPoint());
        if (r >= 0) resultTable.setRowSelectionInterval(r, r);
        if (!e.isPopupTrigger()) return;

        RowSel s = getSelectedRow();
        if (s != null) {
            String ext = getExtFromName(s.name);
            org.abitware.docfinder.index.ConfigManager cm = new org.abitware.docfinder.index.ConfigManager();
            String prog = cm.getOpenWithProgram(ext);
            if (prog != null) {
                rememberedOpenWithItem.setText(new java.io.File(prog).getName());
                rememberedOpenWithItem.setVisible(true);
                // 重新绑定动作（先清旧 listener）
                for (java.awt.event.ActionListener al : rememberedOpenWithItem.getActionListeners()) {
                    rememberedOpenWithItem.removeActionListener(al);
                }
                rememberedOpenWithItem.addActionListener(ev -> openWithProgram(prog, s.path));
            } else {
                rememberedOpenWithItem.setVisible(false);
            }
        } else {
            rememberedOpenWithItem.setVisible(false);
        }

        rowPopup.show(e.getComponent(), e.getX(), e.getY());
    }

    
    private void show(java.awt.event.MouseEvent e) {
        int r = resultTable.rowAtPoint(e.getPoint());
        if (r >= 0) resultTable.setRowSelectionInterval(r, r);
        if (e.isPopupTrigger()) {
            // 刷新 rememberedItem
            RowSel s = getSelectedRow();
            if (s != null) {
                String ext = getExtFromName(s.name);
                org.abitware.docfinder.index.ConfigManager cm = new org.abitware.docfinder.index.ConfigManager();
                String prog = cm.getOpenWithProgram(ext);
             // ③ 弹出菜单前动态刷新
                if (prog != null) {
                    rememberedOpenWithItem.setText(new java.io.File(prog).getName());
                    rememberedOpenWithItem.setVisible(true);
                    for (java.awt.event.ActionListener al : rememberedOpenWithItem.getActionListeners()) {
                        rememberedOpenWithItem.removeActionListener(al);
                    }
                    rememberedOpenWithItem.addActionListener(ev -> openWithProgram(prog, s.path));
                } else {
                    rememberedOpenWithItem.setVisible(false);
                }
            } else {
            	rememberedOpenWithItem.setVisible(false);
            }
            rowPopup.show(e.getComponent(), e.getX(), e.getY());
        }
    }

    /** 导出当前表格到 CSV（UTF-8, 含表头, 逗号分隔, 自动加引号） */
    private void exportResultsToCsv() {
        if (model.getRowCount() == 0) {
            javax.swing.JOptionPane.showMessageDialog(this, "No rows to export.");
            return;
        }
        javax.swing.JFileChooser fc = new javax.swing.JFileChooser();
        fc.setDialogTitle("Export Results to CSV");
        fc.setSelectedFile(new java.io.File("docfinder-results.csv"));
        if (fc.showSaveDialog(this) != javax.swing.JFileChooser.APPROVE_OPTION) return;

        java.io.File out = fc.getSelectedFile();
        String sep = System.lineSeparator();

        try (java.io.PrintWriter pw = new java.io.PrintWriter(
                new java.io.OutputStreamWriter(new java.io.FileOutputStream(out), "UTF-8"))) {

            // 表头
            int cols = model.getColumnCount();
            java.util.List<String> header = new java.util.ArrayList<>();
            for (int c = 0; c < cols; c++) header.add(csvQuote(model.getColumnName(c)));
            pw.write(String.join(",", header) + sep);

            // 数据（按当前排序后的视图行导出）
            int rows = resultTable.getRowCount();
            for (int r = 0; r < rows; r++) {
                java.util.List<String> cells = new java.util.ArrayList<>();
                for (int c = 0; c < cols; c++) {
                    Object val = resultTable.getValueAt(r, c);
                    cells.add(csvQuote(val == null ? "" : val.toString()));
                }
                pw.write(String.join(",", cells) + sep);
            }
            pw.flush();
            statusLabel.setText("CSV exported: " + out.getAbsolutePath());
        } catch (Exception ex) {
            javax.swing.JOptionPane.showMessageDialog(this, "Export failed:\n" + ex.getMessage(),
                    "Error", javax.swing.JOptionPane.ERROR_MESSAGE);
        }
    }
    private static String csvQuote(String s) {
        String t = s.replace("\"", "\"\"");
        return "\"" + t + "\"";
    }

    
    private static String getExtFromName(String name) {
        int i = name.lastIndexOf('.');
        return (i > 0) ? name.substring(i+1).toLowerCase() : "";
    }
    
    /** 用指定程序打开文件（跨平台处理） */
    private void openWithProgram(String programAbsPath, String fileAbsPath) {
        try {
            if (isMac()) {
                // macOS: open -a <App> <file>  (当 programAbsPath 是 .app 或其内部二进制)
                new ProcessBuilder("open", "-a", programAbsPath, fileAbsPath).start();
            } else {
                // Windows / Linux: 直接执行 程序 + 文件
                new ProcessBuilder(programAbsPath, fileAbsPath).start();
            }
        } catch (Exception ex) {
            javax.swing.JOptionPane.showMessageDialog(this, "Open With failed:\n" + ex.getMessage());
        }
    }


    private static class RowSel { // 小工具类
        final String name, path;
        RowSel(String n, String p) { name = n; path = p; }
    }

    private void setClipboard(String s) {
        java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
            .setContents(new java.awt.datatransfer.StringSelection(s), null);
    }

    /** 跨平台“在资源管理器中显示” */
    private void revealInExplorer(String path) throws Exception {
        if (isWindows()) {
            new ProcessBuilder("explorer.exe", "/select,", path).start();
        } else if (isMac()) {
            new ProcessBuilder("open", "-R", path).start();
        } else {
            // Linux：退而求其次，打开所在目录
            java.io.File f = new java.io.File(path);
            new ProcessBuilder("xdg-open", f.getParentFile().getAbsolutePath()).start();
        }
    }

    private void showUsageDialog() {
        String html =
            "<html><body style='width:640px;font-family:sans-serif;font-size:12px;line-height:1.5'>" +
            "<h2>DocFinder - Usage Guide</h2>" +

            "<h3>Overview</h3>" +
            "<ul>" +
            "<li>Fast file-name search (prefix boosted).</li>" +
            "<li>Content search via Apache Tika (read-only parsing).</li>" +
            "<li>Better CJK (Chinese/Japanese) matching with specialized analyzers.</li>" +
            "</ul>" +

            "<h3>Quick Start</h3>" +
            "<ol>" +
            "<li>Open <b>File → Index Sources…</b> to add folders.</li>" +
            "<li>Run <b>File → Index All Sources</b> to build/update the index, or <b>Rebuild Index (Full)</b> to recreate it from scratch.</li>" +
            "<li>Type your query and press <b>Enter</b>.</li>" +
            "</ol>" +

            "<h3>Query Examples</h3>" +
            "<ul>" +
            "<li><code>report*</code> — prefix match on file name</li>" +
            "<li><code>\"project plan\"</code> — phrase match</li>" +
            "<li><code>content:kubernetes AND ingress</code> — content-only query</li>" +
            "<li><code>name:\"設計\"</code> / <code>content:\"設計 仕様\"</code> — Japanese examples</li>" +
            "</ul>" +

            "<h3>Filters</h3>" +
            "<ul>" +
            "<li>Click <b>Filters</b> to toggle filter bar.</li>" +
            "<li><b>Ext(s)</b>: comma-separated, e.g. <code>pdf,docx,txt</code>.</li>" +
            "<li><b>From / To</b>: date range (yyyy-MM-dd) for modified time.</li>" +
            "</ul>" +

            "<h3>Shortcuts & Actions</h3>" +
            "<ul>" +
            "<li><b>Ctrl+Alt+Space</b> — toggle main window</li>" +
            "<li><b>Enter</b> — run search / open selected file in results</li>" +
            "<li><b>Ctrl+C</b> — copy full path; <b>Ctrl+Shift+C</b> — copy file name</li>" +
            "<li><b>Alt+↓</b> — open query history dropdown</li>" +
            "<li><b>Ctrl+Shift+Delete</b> — Clear Search History…</li>" +
            "<li>Right-click a result row: <i>Open / Reveal in Explorer / Copy</i></li>" +
            "</ul>" +

            "<h3>Privacy & Safety</h3>" +
            "<ul>" +
            "<li>Indexing opens files in <b>read-only</b> mode. Contents and mtime are never modified by DocFinder.</li>" +
            "<li>On some NAS/SMB systems, <i>atime</i> (last access time) may be updated by the server when files are read.</li>" +
            "</ul>" +

            "<h3>Notes</h3>" +
            "<ul>" +
            "<li>Very large or encrypted files may have empty previews.</li>" +
            "<li>OCR for scanned PDFs/images is optional (currently disabled by default).</li>" +
            "</ul>" +
            "</body></html>";
        JEditorPane ep = new JEditorPane("text/html", html);
        ep.setEditable(false);
        JScrollPane sp = new JScrollPane(ep);
        sp.setPreferredSize(new Dimension(680, 460));
        JOptionPane.showMessageDialog(this, sp, "Usage Guide", JOptionPane.PLAIN_MESSAGE);
    }

    
    private static String fmtTime(long epochMs) {
        if (epochMs <= 0) return "";
        return new SimpleDateFormat().format(new java.util.Date(epochMs));
    }

    private void applyFilters() {
        org.abitware.docfinder.search.FilterState f = new org.abitware.docfinder.search.FilterState();
        f.exts = org.abitware.docfinder.search.FilterState.parseExts(extField.getText());
        f.fromEpochMs = parseDateMs(fromField.getText());
        f.toEpochMs   = parseDateMs(toField.getText());
        if (searchService != null) searchService.setFilter(f);
    }
    
    private Long parseDateMs(String s) {
        try {
            if (s == null || s.trim().isEmpty()) return null;
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd");
            sdf.setLenient(false);
            return sdf.parse(s.trim()).getTime();
        } catch (Exception e) { return null; }
    }

    private static String fmtSize(long b) {
        // 简易人类可读：B / KB / MB / GB
        final long KB=1024, MB=KB*1024, GB=MB*1024;
        if (b < KB) return b + " B";
        if (b < MB) return String.format("%.1f KB", b/(double)KB);
        if (b < GB) return String.format("%.1f MB", b/(double)MB);
        return String.format("%.1f GB", b/(double)GB);
    }

    
    private static boolean isWindows() { return System.getProperty("os.name").toLowerCase().contains("win"); }
    private static boolean isMac() { return System.getProperty("os.name").toLowerCase().contains("mac"); }

    public JTextField getSearchField() { return searchField; }
    public JTable getResultTable() { return resultTable; }
    public JLabel getStatusLabel() { return statusLabel; }
    public void setSearchService(SearchService svc) { this.searchService = svc; }
}
