package org.abitware.docfinder.ui;

import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.awt.event.KeyEvent;
import java.beans.PropertyChangeListener;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;

import org.abitware.docfinder.ui.components.MenuBarPanel;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.detect.EncodingDetector;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.io.TikaInputStream;
import java.nio.charset.Charset;

import org.apache.tika.parser.txt.CharsetDetector;
import org.apache.tika.parser.txt.CharsetMatch;

import org.abitware.docfinder.ui.components.MenuBarPanel;

import org.abitware.docfinder.search.FilterState;
import org.abitware.docfinder.search.MatchMode;
import org.abitware.docfinder.search.SearchRequest;
import org.abitware.docfinder.search.SearchScope;
import org.abitware.docfinder.search.SearchResult;
import org.abitware.docfinder.search.SearchService;
import org.abitware.docfinder.watch.NetPollerService.PollStats;

import org.abitware.docfinder.ui.LogViewer;
import org.abitware.docfinder.ui.log.JTextAreaAppender;

public class MainWindow extends JFrame implements MenuBarPanel.MenuListener {
	private static final Logger log = LoggerFactory.getLogger(MainWindow.class);
	private LogViewer logViewer;
	private org.abitware.docfinder.watch.LiveIndexService liveService;
	private javax.swing.JCheckBoxMenuItem liveWatchToggle;

	private javax.swing.JCheckBoxMenuItem netPollToggle;
	private org.abitware.docfinder.watch.NetPollerService netPoller;

	// ========= Fields =========
	private SearchService searchService;

	// Top bar: search and filters
	// Search box changed to editable combo box, editor is still JTextField
	private final javax.swing.JComboBox<SearchScope> scopeBox = new javax.swing.JComboBox<>(SearchScope.values());
	private final javax.swing.JComboBox<MatchMode> matchModeBox = new javax.swing.JComboBox<>(MatchMode.values());
	private final javax.swing.JComboBox<String> queryBox = new javax.swing.JComboBox<>();
	private javax.swing.JTextField searchField; // Actual editor
	private final org.abitware.docfinder.search.SearchHistoryManager historyMgr = new org.abitware.docfinder.search.SearchHistoryManager();

	// Popup & "Open With" remembered item (for right-click menu and refresh)
	private JPopupMenu rowPopup;
	private JMenuItem rememberedOpenWithItem;

	private final JTextField extField = new JTextField(); // Comma-separated extensions
	private JFormattedTextField fromField; // yyyy-MM-dd
	private JFormattedTextField toField; // yyyy-MM-dd
	private final JPanel filterBar = new JPanel(new BorderLayout(6, 6)); // Collapsible filter bar

	// Center: results + preview
	private final DefaultTableModel model = new DefaultTableModel(
			new Object[] { "Name", "Path", "Size", "Score", "Created", "Accessed", "Match" }, 0) {
		@Override
		public boolean isCellEditable(int r, int c) {
			return false;
		}
	};

	private final JTable resultTable = new JTable(model);
	private final JEditorPane preview = new JEditorPane("text/html", "");
	private String lastPreviewInner = null;
	private final PropertyChangeListener lafListener;
	private JSplitPane split;

	// Bottom: status bar
	private final JLabel statusLabel = new JLabel("Ready");

	// Preview/search context
	private String lastQuery = "";

	private SearchWorker activeSearchWorker;
	private long searchSequence = 0L;
	private volatile boolean isIndexing = false;
	private final ExecutorService searchExecutor = Executors.newSingleThreadExecutor();

	// ========= Constructor =========
	public MainWindow(SearchService searchService) {
		super("DocFinder");
		setSearchService(searchService); // Use the setter to manage lifecycle

		logViewer = new LogViewer(this);
		JTextAreaAppender.setLogViewer(logViewer);

		setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
		setMinimumSize(new Dimension(900, 560));
		setLocationRelativeTo(null);
		getContentPane().setLayout(new BorderLayout());

		// 1) Top North: search bar + collapsible filter bar
		JPanel north = new JPanel(new BorderLayout());
		north.add(buildTopBar(), BorderLayout.NORTH);
		north.add(buildFilterBar(), BorderLayout.CENTER); // Hidden by default
		getContentPane().add(north, BorderLayout.NORTH);

		// 2) Center: results table + right preview panel
		getContentPane().add(buildCenterAndPreview(), BorderLayout.CENTER);

		// 3) Bottom South: status bar
		getContentPane().add(buildStatusBar(), BorderLayout.SOUTH);

		// 4) Menu bar (File / Help)
		MenuBarPanel menuBar = buildMenuBar();
		menuBar.setMenuListener(this);
		setJMenuBar(menuBar);

		// 5) Right-click menu, shortcuts, table selection listener
		installTablePopupActions(); // Right-click: Open / Reveal / Copy
		installTableShortcuts(); // Enter / Ctrl+C / Ctrl+Shift+C
		resultTable.getSelectionModel().addListSelectionListener(e -> {
			if (!e.getValueIsAdjusting())
				loadPreviewAsync();
		});

		setIconImages(org.abitware.docfinder.ui.IconUtil.loadAppImages());

		// Further: set Taskbar/Dock icon (pick the largest one)
		java.util.List<java.awt.Image> imgs = org.abitware.docfinder.ui.IconUtil.loadAppImages();
		if (!imgs.isEmpty()) {
			java.awt.Image best = imgs.get(imgs.size() - 1);
			org.abitware.docfinder.ui.IconUtil.setAppTaskbarIconIfSupported(best);
		}
		updatePreviewInner("Preview", false);

		lafListener = evt -> {
			if ("lookAndFeel".equals(evt.getPropertyName())) {
				javax.swing.SwingUtilities.invokeLater(this::refreshPreviewForTheme);
			}
		};
		UIManager.addPropertyChangeListener(lafListener);

	}

	/** Top search bar (includes Filters button) */
	private JComponent buildTopBar() {
		JPanel top = new JPanel(new BorderLayout(8, 8));
		top.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));

		scopeBox.setToolTipText("Search scope");
		scopeBox.setMaximumRowCount(SearchScope.values().length);
		scopeBox.setSelectedItem(SearchScope.ALL);

		matchModeBox.setToolTipText("Match mode");
		matchModeBox.setMaximumRowCount(MatchMode.values().length);
		matchModeBox.setSelectedItem(MatchMode.FUZZY);

		// Search box tips
		queryBox.setEditable(true);
		queryBox.setToolTipText("Tips: name:<term>, content:<term>, phrase with quotes, AND/OR, wildcard *");

		// Install placeholder for query box editor (JTextField)
		searchField = (javax.swing.JTextField) queryBox.getEditor().getEditorComponent();
		searchField.putClientProperty("JTextField.placeholderText",
				"Search... (e.g. report*, content:\"zero knowledge\", name:\"瑷▓\")");
		// Immediate search on Enter
		searchField.addActionListener(e -> doSearch());

		// History dropdown on query box action
		queryBox.addActionListener(e -> {
			Object sel = queryBox.getSelectedItem();
			if (sel != null && queryBox.isPopupVisible()) {
				setQueryText(sel.toString());
				doSearch();
			}
		});

		// Load and add history items to query box
		List<String> hist = historyMgr.load();
		for (String s : hist)
			queryBox.addItem(s);

		// Shortcuts: focus search field, clear selection
		queryBox.setSelectedItem(""); // <-- Clear
		searchField.requestFocusInWindow(); // Focus search field

		JButton toggleFilters = new JButton("Filters");
		toggleFilters.addActionListener(e -> filterBar.setVisible(!filterBar.isVisible()));

		scopeBox.addActionListener(e -> rerunIfQueryPresent());
		matchModeBox.addActionListener(e -> rerunIfQueryPresent());

		JPanel centerStrip = new JPanel(new BorderLayout(6, 0));
		centerStrip.add(scopeBox, BorderLayout.WEST);
		centerStrip.add(queryBox, BorderLayout.CENTER);

		JPanel eastStrip = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
		eastStrip.add(matchModeBox);
		eastStrip.add(toggleFilters);

		top.add(new JLabel("Scope:"), BorderLayout.WEST);
		top.add(centerStrip, BorderLayout.CENTER);
		top.add(eastStrip, BorderLayout.EAST);
		return top;
	}


	private String getQueryText() {
		return (searchField == null) ? "" : searchField.getText().trim();
	}

	private void rerunIfQueryPresent() {
		String text = getQueryText();
		if (!text.isEmpty()) {
			doSearch();
		}
	}

	private void setQueryText(String s) {
		if (searchField != null)
			searchField.setText(s);
	}

	/** Filter bar (extension + time range), hidden by default */
	private JComponent buildFilterBar() {
		// Date format for from/to fields
		java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd");
		fromField = new JFormattedTextField(sdf);
		fromField.setColumns(10);
		toField = new JFormattedTextField(sdf);
		toField.setColumns(10);

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
		applyBtn.addActionListener(e -> doSearch());
		row.add(applyBtn);

		filterBar.add(row, BorderLayout.CENTER);
		filterBar.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
		filterBar.setVisible(false); // Hidden by default
		return filterBar;
	}

	/** Center area: results table + preview panel (split) */
	private JComponent buildCenterAndPreview() {
		// Results table basic settings and column widths
		resultTable.setFillsViewportHeight(true);
		resultTable.setRowHeight(22);
		resultTable.setAutoCreateRowSorter(true);

		resultTable.getColumnModel().getColumn(0).setPreferredWidth(240); // Name
		resultTable.getColumnModel().getColumn(1).setPreferredWidth(480); // Path
		resultTable.getColumnModel().getColumn(2).setPreferredWidth(90);  // Size ✅
		resultTable.getColumnModel().getColumn(3).setPreferredWidth(70); // Score
		resultTable.getColumnModel().getColumn(4).setPreferredWidth(130); // Created
		resultTable.getColumnModel().getColumn(5).setPreferredWidth(130); // Accessed
		resultTable.getColumnModel().getColumn(6).setPreferredWidth(110); // Match

		JScrollPane center = new JScrollPane(resultTable);

		// Preview pane: HTML, non-editable
		preview.setEditable(false);
		JScrollPane right = new JScrollPane(preview);
		right.setPreferredSize(new Dimension(360, 560));

		split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, center, right);
		split.setResizeWeight(0.72); // Left 72% / Right 28%
		return split;
	}

	/** Bottom status bar */
	private JComponent buildStatusBar() {
		JPanel bottom = new JPanel(new BorderLayout());
		bottom.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
		bottom.add(statusLabel, BorderLayout.WEST);
		return bottom;
	}

	/** Menu bar (File / Help) */
	private MenuBarPanel buildMenuBar() {
		MenuBarPanel bar = new MenuBarPanel();
		return bar;
	}

	private void toggleNetPolling() {
		final boolean enable = netPollToggle.isSelected();
		netPollToggle.setEnabled(false);
		statusLabel.setText(enable ? "Enabling network polling..." : "Disabling network polling...");
		new javax.swing.SwingWorker<Void, Void>() {
			private String message;
			private int messageType = javax.swing.JOptionPane.ERROR_MESSAGE;
			private int minutes = 0;
			private org.abitware.docfinder.watch.NetPollerService newPoller;
			private boolean success = false;

			@Override
			protected Void doInBackground() {
				try {
					org.abitware.docfinder.index.SourceManager sm = new org.abitware.docfinder.index.SourceManager();
					java.util.List<org.abitware.docfinder.index.SourceManager.SourceEntry> entries = sm.loadEntries();
					java.util.List<java.nio.file.Path> netRoots = new java.util.ArrayList<>();
					for (org.abitware.docfinder.index.SourceManager.SourceEntry e : entries) {
						if (e.network) {
							netRoots.add(java.nio.file.Paths.get(e.path));
						}
					}
					if (enable) {
						if (netRoots.isEmpty()) {
							message = "No network sources.";
							messageType = javax.swing.JOptionPane.INFORMATION_MESSAGE;
							return null;
						}
						org.abitware.docfinder.index.ConfigManager cm = new org.abitware.docfinder.index.ConfigManager();
						minutes = cm.getPollingMinutes();
						org.abitware.docfinder.index.IndexSettings s = cm.loadIndexSettings();
						org.abitware.docfinder.watch.NetPollerService existing = netPoller;
						if (existing != null) {
							try { existing.close(); } catch (Exception ignore) {}
						}
						org.abitware.docfinder.watch.NetPollerService poller = new org.abitware.docfinder.watch.NetPollerService(sm.getIndexDir(), s, netRoots);
						poller.start(minutes);
						cm.setPollingEnabled(true);
						newPoller = poller;
					} else {
						org.abitware.docfinder.watch.NetPollerService existing = netPoller;
						if (existing != null) {
							try { existing.close(); } catch (Exception ignore) {}
						}
						new org.abitware.docfinder.index.ConfigManager().setPollingEnabled(false);
						newPoller = null;
					}
					success = true;
				} catch (Exception ex) {
					message = (enable ? "Network polling failed:\n" : "Disable network polling failed:\n") + ex.getMessage();
				}
				return null;
			}

			@Override
			protected void done() {
				netPollToggle.setEnabled(true);
				if (!success) {
					netPollToggle.setSelected(!enable);
					if (message != null) {
						javax.swing.JOptionPane.showMessageDialog(MainWindow.this, message, enable ? "Enable Network Polling" : "Disable Network Polling", messageType);
					}
					statusLabel.setText(netPoller != null ? "Network polling: ON" : "Network polling: OFF");
				} else {
					if (enable) {
						netPoller = newPoller;
						statusLabel.setText("Network polling: ON (every " + minutes + " min)");
					} else {
						netPoller = null;
						statusLabel.setText("Network polling: OFF");
					}
				}
			}
		}.execute();
	}

	private void pollOnceNow() {
		org.abitware.docfinder.index.SourceManager sm = new org.abitware.docfinder.index.SourceManager();
		java.util.List<org.abitware.docfinder.index.SourceManager.SourceEntry> entries = sm.loadEntries();
		java.util.List<java.nio.file.Path> netRoots = new java.util.ArrayList<>();
		for (org.abitware.docfinder.index.SourceManager.SourceEntry e : entries) {
			if (e.network)
				netRoots.add(java.nio.file.Paths.get(e.path));
		}
		if (netRoots.isEmpty()) {
			JOptionPane.showMessageDialog(this, "No network sources.");
			return;
		}

		org.abitware.docfinder.index.ConfigManager cm = new org.abitware.docfinder.index.ConfigManager();
		org.abitware.docfinder.index.IndexSettings s = cm.loadIndexSettings();

		// Always create a new, dedicated poller for this manual run.
		final org.abitware.docfinder.watch.NetPollerService manualPoller = new org.abitware.docfinder.watch.NetPollerService(sm.getIndexDir(), s, netRoots);

		setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.WAIT_CURSOR));
        statusLabel.setText("Polling network sources…");

		new javax.swing.SwingWorker<org.abitware.docfinder.watch.NetPollerService.PollStats, Void>() {
			@Override
			protected org.abitware.docfinder.watch.NetPollerService.PollStats doInBackground() throws Exception {
				return manualPoller.pollNowAsync().get();
			}

			@Override
			protected void done() {
				try {
					PollStats st = get();
					statusLabel.setText(String.format("Polled: scanned=%d, created=%d, modified=%d, deleted=%d | %d ms",
							st.scannedFiles, st.created, st.modified, st.deleted, st.durationMs));
					if (lastQuery != null && !lastQuery.trim().isEmpty())
						doSearch();
				} catch (Exception ex) {
					JOptionPane.showMessageDialog(MainWindow.this, "Poll failed:\n" + ex.getMessage(), "Error",
							JOptionPane.ERROR_MESSAGE);
				} finally {
					setCursor(java.awt.Cursor.getDefaultCursor());
					// Always close the dedicated manual poller.
					manualPoller.close();
				}
			}
		}.execute();
	}

	private void toggleLiveWatch() {
		final boolean enable = liveWatchToggle.isSelected();
		liveWatchToggle.setEnabled(false);
		statusLabel.setText(enable ? "Enabling live watch..." : "Disabling live watch...");
		new javax.swing.SwingWorker<Void, Void>() {
			private String message;
			private int messageType = javax.swing.JOptionPane.ERROR_MESSAGE;
			private int rootCount = 0;
			private org.abitware.docfinder.watch.LiveIndexService newService;
			private boolean success = false;

			@Override
			protected Void doInBackground() {
				try {
					org.abitware.docfinder.index.SourceManager sm = new org.abitware.docfinder.index.SourceManager();
					java.util.List<org.abitware.docfinder.index.SourceManager.SourceEntry> entries = sm.loadEntries();
					java.util.List<java.nio.file.Path> localRoots = new java.util.ArrayList<>();
					for (org.abitware.docfinder.index.SourceManager.SourceEntry e : entries) {
						if (!e.network) {
							localRoots.add(java.nio.file.Paths.get(e.path));
						}
					}
					if (enable) {
						if (localRoots.isEmpty()) {
							message = "No local sources. Use 'Index Sources...' first.";
							messageType = javax.swing.JOptionPane.INFORMATION_MESSAGE;
							return null;
						}
						org.abitware.docfinder.index.ConfigManager cm = new org.abitware.docfinder.index.ConfigManager();
						org.abitware.docfinder.index.IndexSettings s = cm.loadIndexSettings();
						org.abitware.docfinder.watch.LiveIndexService existing = liveService;
						if (existing != null) {
							try { existing.close(); } catch (Exception ignore) {}
						}
						org.abitware.docfinder.watch.LiveIndexService service = new org.abitware.docfinder.watch.LiveIndexService(sm.getIndexDir(), s, localRoots);
						service.start();
						newService = service;
						rootCount = localRoots.size();
					} else {
						org.abitware.docfinder.watch.LiveIndexService existing = liveService;
						if (existing != null) {
							try { existing.close(); } catch (Exception ignore) {}
						}
						newService = null;
					}
					success = true;
				} catch (Exception ex) {
					message = (enable ? "Live watch failed:\n" : "Disable live watch failed:\n") + ex.getMessage();
				}
				return null;
			}

			@Override
			protected void done() {
				liveWatchToggle.setEnabled(true);
				if (!success) {
					liveWatchToggle.setSelected(!enable);
					if (message != null) {
						javax.swing.JOptionPane.showMessageDialog(MainWindow.this, message, enable ? "Enable Live Watch" : "Disable Live Watch", messageType);
					}
					statusLabel.setText(liveService != null ? "Live watch: ON" : "Live watch: OFF");
				} else {
					if (enable) {
						liveService = newService;
						statusLabel.setText("Live watch: ON (" + rootCount + " local root(s))");
					} else {
						liveService = null;
						statusLabel.setText("Live watch: OFF");
					}
				}
			}
		}.execute();
	}

	/** Clear search history: confirm -> clear persisted file content -> clear dropdown list -> clear input box */
	private void clearSearchHistory() {
		int ret = javax.swing.JOptionPane.showConfirmDialog(this,
				"This will remove all saved search queries.\nProceed?", "Clear Search History",
				javax.swing.JOptionPane.OK_CANCEL_OPTION, javax.swing.JOptionPane.WARNING_MESSAGE);
		if (ret != javax.swing.JOptionPane.OK_OPTION)
			return;

		try {
			// 1) Clear persisted history
			historyMgr.save(Collections.emptyList());

			// 2) Clear dropdown model
			javax.swing.DefaultComboBoxModel<String> m = (javax.swing.DefaultComboBoxModel<String>) queryBox.getModel();
			m.removeAllElements();

			// 3) Clear current input
			setQueryText("");

			// 4) Status prompt
			statusLabel.setText("Search history cleared.");
			// Preview area light prompt
			updatePreviewInner("Search history cleared.");
		} catch (Exception ex) {
			javax.swing.JOptionPane.showMessageDialog(this, "Failed to clear history:\n" + ex.getMessage(), "Error",
					javax.swing.JOptionPane.ERROR_MESSAGE);
		}
	}

	/** Result table shortcuts: Enter opens, Ctrl+C copies path, Ctrl+Shift+C copies name */
	private void installTableShortcuts() {
		InputMap im = resultTable.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
		ActionMap am = resultTable.getActionMap();

		im.put(KeyStroke.getKeyStroke("ENTER"), "open");
		im.put(KeyStroke.getKeyStroke("ctrl C"), "copyPath");
		im.put(KeyStroke.getKeyStroke("ctrl shift C"), "copyName");

		am.put("open", new AbstractAction() {
			@Override
			public void actionPerformed(java.awt.event.ActionEvent e) {
				RowSel s = getSelectedRow();
				if (s != null) {
					String p = org.abitware.docfinder.util.Utils.toExplorerFriendlyPath(s.path);
					try {
						Desktop.getDesktop().open(new java.io.File(p));
					} catch (Exception ignore) {
					}
				}
			}
		});
		am.put("copyPath", new AbstractAction() {
			@Override
			public void actionPerformed(java.awt.event.ActionEvent e) {
				RowSel s = getSelectedRow();
				if (s != null)
					setClipboard(s.path);
			}
		});
		am.put("copyName", new AbstractAction() {
			@Override
			public void actionPerformed(java.awt.event.ActionEvent e) {
				RowSel s = getSelectedRow();
				if (s != null)
					setClipboard(s.name);
			}
		});
	}

	private void manageSources() {
		// 1) Open source manager dialog (modal)
		org.abitware.docfinder.ui.ManageSourcesDialog dlg = new org.abitware.docfinder.ui.ManageSourcesDialog(this);
		dlg.setVisible(true);

		if (!dlg.isSourcesChanged()) {
			return;
		}

		// 2) Check if need to restart Live Watch / Poller
		boolean needRestart = (liveWatchToggle != null && liveWatchToggle.isSelected())
				|| (netPollToggle != null && netPollToggle.isSelected());
		if (!needRestart)
			return;

		int ans = javax.swing.JOptionPane.showConfirmDialog(this, "Sources updated. Restart watchers/polling now?",
				"Apply Changes", javax.swing.JOptionPane.OK_CANCEL_OPTION, javax.swing.JOptionPane.QUESTION_MESSAGE);
		if (ans != javax.swing.JOptionPane.OK_OPTION)
			return;

		// 3) Restart watchers/polling if needed
		// Delay to ensure toggle action is processed
		setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.WAIT_CURSOR));
        statusLabel.setText("Applying source changes…");

		new javax.swing.SwingWorker<Void, Void>() {
			@Override
			protected Void doInBackground() {
				try {
					if (liveWatchToggle != null && liveWatchToggle.isSelected()) {
						// Turn off
						javax.swing.SwingUtilities.invokeLater(() -> {
							liveWatchToggle.setSelected(false);
							toggleLiveWatch();
						});
						// Delay for toggle to take effect
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
				} catch (InterruptedException ignore) {
				}
				return null;
			}

			@Override
			protected void done() {
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
			@Override
			protected Integer doInBackground() throws Exception {
				org.abitware.docfinder.index.ConfigManager cm = new org.abitware.docfinder.index.ConfigManager();
				org.abitware.docfinder.index.IndexSettings s = cm.loadIndexSettings();
				org.abitware.docfinder.index.LuceneIndexer idx = new org.abitware.docfinder.index.LuceneIndexer(
						indexDir, s);

				int total = 0;
				for (java.nio.file.Path p : sources) {
					total += idx.indexFolder(p);
				}
				return total;
			}

			@Override
			protected void done() {
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
		if (s == null) {
			updatePreviewInner("No selection.");
			return;
		}
		java.nio.file.Path path;
		try {
			path = java.nio.file.Paths.get(s.path);
		} catch (Exception ex) {
			updatePreviewInner("Preview unavailable.");
			return;
		}
		if (java.nio.file.Files.isDirectory(path)) {
			updatePreviewInner("Loading folder...", false);
			final java.nio.file.Path dir = path;
			new javax.swing.SwingWorker<String, Void>() {
				@Override
				protected String doInBackground() {
					return buildFolderPreviewHtml(dir, 200);
				}

				@Override
				protected void done() {
					try {
						updatePreviewInner(get());
				} catch (Exception ex) {
						updatePreviewInner("Preview failed.");
					}
				}
			}.execute();
			return;
		}
		if (!java.nio.file.Files.isRegularFile(path)) {
			updatePreviewInner("File not found.");
			return;
		}
		updatePreviewInner("Loading preview...", false);
		final java.nio.file.Path target = path;
		new javax.swing.SwingWorker<String, Void>() {
			@Override
			protected String doInBackground() throws Exception {
				final int MAX_CHARS = 60_000;
				String text = loadPreviewText(target, MAX_CHARS);
				if (text == null || text.trim().isEmpty()) {
					return "(No text content.)";
				}

				String q = (lastQuery == null) ? "" : lastQuery.trim();
				String[] terms = tokenizeForHighlight(q);
				String snippet = makeSnippet(text, terms, 300);
				String html = toHtml(snippet, terms);
				return html;
			}

			@Override
			protected void done() {
				try {
					updatePreviewInner(get(), false);
				} catch (Exception ex) {
					updatePreviewInner("Preview failed.");
				}
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

	private String loadPreviewText(java.nio.file.Path file, int maxChars) {
		if (file == null || maxChars <= 0) {
			return "";
		}
		String viaTika = extractTextHead(file, maxChars);
		if (viaTika != null && !viaTika.trim().isEmpty()) {
			return viaTika;
		}
		return readTextFallback(file, maxChars);
	}

	private String readTextFallback(java.nio.file.Path file, int maxChars) {
		try (java.io.InputStream is = java.nio.file.Files.newInputStream(file, java.nio.file.StandardOpenOption.READ)) {
			// First, try Tika's CharsetDetector
			CharsetDetector detector = new CharsetDetector();
			detector.setText(is);
			CharsetMatch match = detector.detect();
			if (match != null && match.getConfidence() > 50) { // Check confidence level
				try {
					String text = readTextWithCharset(file, maxChars, Charset.forName(match.getName()));
					if (text != null && !text.trim().isEmpty()) {
						return text;
					}
				} catch (Exception ignore) {
					// Fallback to other candidates if Tika's detected charset fails
				}
			}
		} catch (Exception ignore) {
			// Fallback to other candidates if Tika detection fails
		}

		// Fallback to existing charset candidates
		java.util.LinkedHashSet<java.nio.charset.Charset> candidates = new java.util.LinkedHashSet<>();
		java.nio.charset.Charset bom = detectBomCharset(file);
		if (bom != null) {
			candidates.add(bom);
		}
		candidates.add(java.nio.charset.StandardCharsets.UTF_8);
		candidates.add(java.nio.charset.StandardCharsets.UTF_16LE);
		candidates.add(java.nio.charset.StandardCharsets.UTF_16BE);
		try {
			candidates.add(java.nio.charset.Charset.forName("windows-1252"));
		} catch (Exception ignore) {
		}
		for (java.nio.charset.Charset cs : candidates) {
			if (cs == null) {
				continue;
			}
			try {
				String text = readTextWithCharset(file, maxChars, cs);
				if (text != null && !text.trim().isEmpty()) {
					return text;
				}
			} catch (Exception ignore) {
			}
		}
		return "";
	}

	private String readTextWithCharset(java.nio.file.Path file, int maxChars, java.nio.charset.Charset charset) throws java.io.IOException {
		if (file == null || charset == null || maxChars <= 0) {
			return "";
		}
		char[] buffer = new char[4096];
		StringBuilder sb = new StringBuilder(Math.min(maxChars, 65_536));
		try (java.io.Reader reader = new java.io.BufferedReader(
				new java.io.InputStreamReader(java.nio.file.Files.newInputStream(file, java.nio.file.StandardOpenOption.READ), charset))) {
			int remaining = maxChars;
			while (remaining > 0) {
				int n = reader.read(buffer, 0, Math.min(buffer.length, remaining));
				if (n < 0) {
					break;
				}
				sb.append(buffer, 0, n);
				remaining -= n;
			}
		}
		if (sb.length() > 0 && sb.charAt(0) == '\uFEFF') {
			sb.deleteCharAt(0);
		}
		return sb.toString();
	}

	private java.nio.charset.Charset detectBomCharset(java.nio.file.Path file) {
		if (file == null) {
			return null;
		}
		try (java.io.InputStream is = java.nio.file.Files.newInputStream(file, java.nio.file.StandardOpenOption.READ)) {
			byte[] bom = new byte[3];
			int n = is.read(bom);
			if (n >= 3 && bom[0] == (byte) 0xEF && bom[1] == (byte) 0xBB && bom[2] == (byte) 0xBF) {
				return java.nio.charset.StandardCharsets.UTF_8;
			}
			if (n >= 2) {
				if (bom[0] == (byte) 0xFE && bom[1] == (byte) 0xFF) {
					return java.nio.charset.StandardCharsets.UTF_16BE;
				}
				if (bom[0] == (byte) 0xFF && bom[1] == (byte) 0xFE) {
					return java.nio.charset.StandardCharsets.UTF_16LE;
				}
			}
		} catch (Exception ignore) {
		}
		return null;
	}

    // 从查询串里提取要高亮的词（非常简化：去掉字段前缀/引号/AND/OR）
	private String[] tokenizeForHighlight(String q) {
		if (q == null)
			return new String[0];
		q = q.replaceAll("(?i)\\b(name|content|path):", " "); // 鍘诲瓧娈靛墠缂€
		q = q.replace("\"", " ").replace("'", " ");
		q = q.replaceAll("(?i)\\bAND\\b|\\bOR\\b|\\bNOT\\b", " ");
		q = q.trim();
		if (q.isEmpty())
			return new String[0];
        // 分词：按空白切；中日文情况下直接保留整段词
		String[] arr = q.split("\\s+");
		java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
		for (String t : arr) {
			t = t.trim();
			if (t.isEmpty() || "*".equals(t))
				continue;
			set.add(t);
		}
		return set.toArray(new String[0]);
	}

    // 生成包含第一个命中的简短片段（上下文 window）
	private String makeSnippet(String text, String[] terms, int window) {
		if (terms.length == 0)
			return text.substring(0, Math.min(window, text.length()));
		String lower = text.toLowerCase();
		int pos = -1;
		for (String t : terms) {
			int p = lower.indexOf(t.toLowerCase());
			if (p >= 0 && (pos == -1 || p < pos))
				pos = p;
		}
		if (pos == -1)
			return text.substring(0, Math.min(window, text.length()));
		int start = Math.max(0, pos - window / 2);
		int end = Math.min(text.length(), start + window);
		return text.substring(start, end);
	}

    // 将片段转成简单 HTML 并高亮 <mark>
	private String toHtml(String snippet, String[] terms) {
		String esc = snippet.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
		for (String t : terms) {
			if (t.isEmpty())
				continue;
			try {
				esc = esc.replaceAll("(?i)" + java.util.regex.Pattern.quote(t), "<mark>$0</mark>");
			} catch (Exception ignore) {
			}
		}
		return esc.replace("\n", "<br/>");
	}

	private String buildFolderPreviewHtml(java.nio.file.Path dir, int maxEntries) {
		StringBuilder inner = new StringBuilder();
		boolean dark = isDarkColor(preview.getBackground());
		String dirColor = dark ? "#8ab4ff" : "#066";
		String metaColor = dark ? "#bbbbbb" : "#999";
		String title = (dir.getFileName() == null) ? dir.toString() : dir.getFileName().toString();
		inner.append("<h3 style='margin-top:0'>").append(htmlEscape(title)).append("</h3>");

		java.util.List<java.nio.file.Path> entries = new java.util.ArrayList<>();
		boolean truncated = false;
		try (java.util.stream.Stream<java.nio.file.Path> stream = java.nio.file.Files.list(dir)) {
			java.util.Comparator<java.nio.file.Path> comp = (a, b) -> {
				try {
					boolean da = java.nio.file.Files.isDirectory(a);
					boolean db = java.nio.file.Files.isDirectory(b);
					if (da != db) return da ? -1 : 1;
				} catch (Exception ignore) {
				}
				String na = (a.getFileName() == null) ? a.toString() : a.getFileName().toString();
				String nb = (b.getFileName() == null) ? b.toString() : b.getFileName().toString();
				return na.compareToIgnoreCase(nb);
			};
			stream.sorted(comp).limit((long) maxEntries + 1).forEach(entries::add);
		} catch (Exception ex) {
			return "<p>" + htmlEscape("Failed to read folder: " + ex.getMessage()) + "</p>";
		}

		if (entries.size() > maxEntries) {
			truncated = true;
			entries = new java.util.ArrayList<>(entries.subList(0, maxEntries));
		}

		if (entries.isEmpty()) {
			inner.append("<p>(Empty folder)</p>");
		} else {
			inner.append("<ul style='margin:0;padding-left:16px'>");
			for (java.nio.file.Path child : entries) {
				boolean isDir = false;
				try {
					isDir = java.nio.file.Files.isDirectory(child);
				} catch (Exception ignore) {
				}
				String name = (child.getFileName() == null) ? child.toString() : child.getFileName().toString();
				inner.append("<li>");
				if (isDir) {
					inner.append(String.format("<span style='color:%s;font-weight:bold;'>[DIR]</span> ", dirColor));
				}
				inner.append(htmlEscape(name));
				if (!isDir) {
					try {
						long size = java.nio.file.Files.size(child);
						inner.append(String.format(" <span style='color:%s'>(%s)</span>", metaColor, htmlEscape(fmtSize(size))));
					} catch (Exception ignore) {
					}
				}
				inner.append("</li>");
			}
			inner.append("</ul>");
			if (truncated) {
				inner.append(String.format("<p style='color:%s;margin-top:8px'>(Showing first %d items)</p>", metaColor, maxEntries));
			}
		}

		return inner.toString();
	}


	private String htmlEscape(String s) {
		if (s == null) return "";
		String out = s;
		out = out.replace("&", "&amp;");
		out = out.replace("<", "&lt;");
		out = out.replace(">", "&gt;");
		out = out.replace("\"", "&quot;");
		out = out.replace("'", "&#39;");
		return out;
	}

	private String htmlWrap(String inner) {
		java.awt.Color bg = preview.getBackground();
		java.awt.Color fg = preview.getForeground();
		String textColor = (fg != null) ? toCssColor(fg) : (isDarkColor(bg) ? "#f5f5f5" : "#333333");
		String linkColor = isDarkColor(bg) ? "#8ab4ff" : "#0645ad";
		String bgColor = (bg == null) ? null : toCssColor(bg);
		StringBuilder sb = new StringBuilder();
		sb.append("<html><head>");
		sb.append("<style>body{font-family:sans-serif;font-size:11px;line-height:1.4;padding:8px;");
		sb.append("color:").append(textColor).append(';');
		if (bgColor != null) {
			sb.append("background-color:").append(bgColor).append(';');
		}
		sb.append("}body a{color:").append(linkColor).append(";}</style>");
		sb.append("</head><body>");
		sb.append(inner);
		sb.append("</body></html>");
		return sb.toString();
	}

	private void updatePreviewInner(String inner) {
		updatePreviewInner(inner, true);
	}

	private void updatePreviewInner(String inner, boolean resetCaret) {
		if (inner == null) inner = "";
		lastPreviewInner = inner;
		preview.setText(htmlWrap(inner));
		if (resetCaret) {
			try {
				preview.setCaretPosition(0);
			} catch (Exception ignore) {
			}
		}
	}

	private void refreshPreviewForTheme() {
		if (lastPreviewInner != null) {
			updatePreviewInner(lastPreviewInner, false);
		}
	}


	private boolean isDarkColor(java.awt.Color c) {
		if (c == null) return false;
		double luminance = (0.2126 * c.getRed() + 0.7152 * c.getGreen() + 0.0722 * c.getBlue()) / 255d;
		return luminance < 0.45;
	}

	private String toCssColor(java.awt.Color c) {
		if (c == null) return null;
		return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
	}

	private RowSel getSelectedRow() {
		int row = resultTable.getSelectedRow();
		if (row < 0)
			return null;
		String name = String.valueOf(resultTable.getValueAt(row, 0));
		String path = String.valueOf(resultTable.getValueAt(row, 1));
		return new RowSel(name, path);
	}

	private void chooseAndIndexFolder() {
		JFileChooser fc = new JFileChooser();
		fc.setDialogTitle("Choose a folder to index");
		fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
		if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION)
			return;

		java.io.File folder = fc.getSelectedFile();
		statusLabel.setText("Indexing: " + folder.getAbsolutePath() + " ...");

        // 索引目录：用户主目录下 .docfinder/index
        java.nio.file.Path indexDir = java.nio.file.Paths.get(System.getProperty("user.home"), ".docfinder", "index");

		new javax.swing.SwingWorker<Integer, Void>() {
			@Override
			protected Integer doInBackground() throws Exception {
				org.abitware.docfinder.index.ConfigManager cm = new org.abitware.docfinder.index.ConfigManager();
				org.abitware.docfinder.index.IndexSettings s = cm.loadIndexSettings();
				org.abitware.docfinder.index.LuceneIndexer idx = new org.abitware.docfinder.index.LuceneIndexer(
						indexDir, s);

				return idx.indexFolder(folder.toPath());
			}

			@Override
			protected void done() {
				try {
					int n = get();
					statusLabel.setText("Indexed files: " + n + "  |  Index: " + indexDir.toString());
                    // 切换到 Lucene 搜索服务
					try {
						setSearchService(new org.abitware.docfinder.search.LuceneSearchService(indexDir));
					} catch (java.io.IOException ioEx) {
						statusLabel.setText("Failed to switch search service: " + ioEx.getMessage());
						ioEx.printStackTrace();
					}
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

		JSpinner maxMb = new JSpinner(new SpinnerNumberModel((int) s.maxFileMB, 1, 1024, 1));
		JSpinner timeout = new JSpinner(new SpinnerNumberModel(s.parseTimeoutSec, 1, 120, 1));
		JTextField include = new JTextField(String.join(",", s.includeExt));
		JTextArea exclude = new JTextArea(String.join(";", s.excludeGlob));
		exclude.setRows(3);

		JPanel p = new JPanel(new java.awt.GridBagLayout());
		java.awt.GridBagConstraints c = new java.awt.GridBagConstraints();
		c.insets = new java.awt.Insets(4, 4, 4, 4);
		c.fill = java.awt.GridBagConstraints.HORIZONTAL;
		c.weightx = 1;
		int r = 0;
		c.gridx = 0;
		c.gridy = r;
		p.add(new JLabel("Max file size (MB):"), c);
		c.gridx = 1;
		p.add(maxMb, c);
		r++;
		c.gridx = 0;
		c.gridy = r;
		p.add(new JLabel("Parse timeout (sec):"), c);
		c.gridx = 1;
		p.add(timeout, c);
		r++;
		c.gridx = 0;
		c.gridy = r;
		p.add(new JLabel("Include extensions (comma):"), c);
		c.gridx = 1;
		p.add(include, c);
		r++;
		c.gridx = 0;
		c.gridy = r;
		p.add(new JLabel("Exclude globs (semicolon):"), c);
		c.gridx = 1;
		p.add(new JScrollPane(exclude), c);

		int ret = JOptionPane.showConfirmDialog(this, p, "Indexing Settings", JOptionPane.OK_CANCEL_OPTION,
				JOptionPane.PLAIN_MESSAGE);
		if (ret == JOptionPane.OK_OPTION) {
			s.maxFileMB = ((Number) maxMb.getValue()).longValue();
			s.parseTimeoutSec = ((Number) timeout.getValue()).intValue();
			s.includeExt = new java.util.ArrayList<>(
					FilterState.parseExts(include.getText()));
			s.excludeGlob = java.util.Arrays.asList(exclude.getText().split(";"));
			cm.saveIndexSettings(s);
			statusLabel.setText("Index settings saved.");
		}
	}

	/** Rebuild index (full): delete old index, re-index all sources */
	private void rebuildAllSources() {
		if (isIndexing) {
			JOptionPane.showMessageDialog(this, "An indexing operation is already in progress.");
			return;
		}

		org.abitware.docfinder.index.SourceManager sm = new org.abitware.docfinder.index.SourceManager();
		java.util.List<java.nio.file.Path> sources = sm.load();
		if (sources.isEmpty()) {
			JOptionPane.showMessageDialog(this, "No sources configured. Use 'Index Sources...' first.");
			return;
		}
		org.abitware.docfinder.index.ConfigManager cm = new org.abitware.docfinder.index.ConfigManager();
		org.abitware.docfinder.index.IndexSettings s = cm.loadIndexSettings();

		java.nio.file.Path indexDir = sm.getIndexDir();

		// Disable UI and set indexing flag
		isIndexing = true;
		searchField.setEnabled(false);
		queryBox.setEnabled(false);
		scopeBox.setEnabled(false);
		matchModeBox.setEnabled(false);
        statusLabel.setText("Rebuilding index (full)… Searching is disabled.");
		long t0 = System.currentTimeMillis();

		new javax.swing.SwingWorker<Integer, Void>() {
			@Override
			protected Integer doInBackground() throws Exception {
				org.abitware.docfinder.index.LuceneIndexer idx = new org.abitware.docfinder.index.LuceneIndexer(
						indexDir, s);
				return idx.indexFolders(sources, true); // ✅ full = true
			}

			@Override
			protected void done() {
				try {
					int n = get();
					long ms = System.currentTimeMillis() - t0;
					statusLabel.setText("Rebuilt files: " + n + " | Time: " + ms + " ms | Index: " + indexDir);
					try {
						setSearchService(new org.abitware.docfinder.search.LuceneSearchService(indexDir));
					} catch (java.io.IOException ioEx) {
						statusLabel.setText("Failed to switch search service after rebuild: " + ioEx.getMessage());
						ioEx.printStackTrace();
					}
				} catch (Exception ex) {
					statusLabel.setText("Rebuild failed: " + ex.getMessage());
				} finally {
					// Re-enable UI and clear indexing flag
					isIndexing = false;
					searchField.setEnabled(true);
					queryBox.setEnabled(true);
					scopeBox.setEnabled(true);
					matchModeBox.setEnabled(true);
				}
			}
		}.execute();
	}

	private SearchScope getSelectedScope() {
		Object sel = scopeBox.getSelectedItem();
		return (sel instanceof SearchScope) ? (SearchScope) sel : SearchScope.ALL;
	}

	private MatchMode getSelectedMatchMode() {
		Object sel = matchModeBox.getSelectedItem();
		return (sel instanceof MatchMode) ? (MatchMode) sel : MatchMode.FUZZY;
	}

	private void doSearch() {
		log.debug("doSearch() called.");
		if (isIndexing) {
			statusLabel.setText("Indexing in progress, searching is disabled.");
			return;
		}

		if (searchService == null) {

			statusLabel.setText("Search service unavailable.");

			return;

		}



		FilterState filters = buildFilterState();

		String q = (searchField == null) ? "" : searchField.getText().trim();

		lastQuery = q;



		if (activeSearchWorker != null) {

			activeSearchWorker.cancel(true);

		}



		if (q.isEmpty()) {

			model.setRowCount(0);

			resultTable.clearSelection();

			updatePreviewInner("Enter a query to search.");

			statusLabel.setText("Enter a query to search.");

			return;

		}



		long token = ++searchSequence;

		SearchWorker worker = new SearchWorker(token, q, filters, getSelectedScope(), getSelectedMatchMode());

		activeSearchWorker = worker;



		statusLabel.setText("Searching...");

		updatePreviewInner("Searching...", false);

		searchExecutor.submit(worker);

	}



	private void populateResults(String query, List<SearchResult> list, long elapsedMs) {

		if (list == null) {

			list = Collections.emptyList();

		}



		model.setRowCount(0);

		for (SearchResult r : list) {

			model.addRow(new Object[] { r.name, r.path, fmtSize(r.sizeBytes),

				String.format("%.3f", r.score), fmtTime(r.ctime), fmtTime(r.atime),

				(r.match == null ? "" : r.match) });

		}



		if (list.isEmpty()) {

			statusLabel.setText(String.format("No results. | %d ms", elapsedMs));

			resultTable.clearSelection();

			updatePreviewInner("No results.");

		} else {

			statusLabel.setText(String.format("Results: %d  |  %d ms", list.size(), elapsedMs));

			resultTable.setRowSelectionInterval(0, 0);

		}



		addToHistory(query);

	}



	private class SearchWorker extends javax.swing.SwingWorker<List<SearchResult>, Void> {

		private final long token;

		private final String query;

		private final FilterState filter;

		private final SearchScope scope;

		private final MatchMode matchMode;

		private final long startedAt = System.currentTimeMillis();



		SearchWorker(long token, String query, FilterState filter, SearchScope scope, MatchMode matchMode) {
			this.token = token;
			this.query = query;
			this.filter = filter;
			this.scope = (scope == null) ? SearchScope.ALL : scope;
			this.matchMode = (matchMode == null) ? MatchMode.FUZZY : matchMode;
			log.debug("SearchWorker created for query: {} with token: {}", query, token);
		}



		@Override

		protected List<SearchResult> doInBackground() {
			log.debug("SearchWorker.doInBackground() started for token: {}", token);
			if (isCancelled()) {
				log.debug("SearchWorker.doInBackground() for token {} was cancelled before execution.", token);
				return Collections.emptyList();
			}
			if (searchService == null) {
				log.warn("SearchService is null in SearchWorker.doInBackground() for token: {}", token);
				return Collections.emptyList();

			}

			SearchRequest request = new SearchRequest(query, 100, filter, scope, matchMode);
			log.debug("Executing search for token: {}", token);
			List<SearchResult> results = searchService.search(request);
			log.debug("Search for token {} completed with {} results.", token, results.size());
			return results;

		}



		@Override

		protected void done() {
			log.debug("SearchWorker.done() called for token: {}. Current searchSequence: {}", token, searchSequence);
			if (token != searchSequence) {
				log.debug("SearchWorker.done() for token {} is stale, ignoring.", token);
				return;
			}



			if (activeSearchWorker == this) {

				activeSearchWorker = null;

			}



			if (isCancelled()) {

				return;

			}



			List<SearchResult> list;

			try {

				list = get();

			} catch (java.util.concurrent.CancellationException ex) {

				return;

			} catch (InterruptedException ex) {

				return;

			} catch (java.util.concurrent.ExecutionException ex) {

				Throwable cause = ex.getCause();

				statusLabel.setText("Search failed: " + (cause != null ? cause.getMessage() : ex.getMessage()));

				return;

			} catch (Exception ex) {

				statusLabel.setText("Search failed: " + ex.getMessage());

				return;

			}



			if (token != searchSequence) {

				return;

			}



			long elapsed = Math.max(0L, System.currentTimeMillis() - startedAt);

			populateResults(query, list, elapsed);

		}

	}



	private void addToHistory(String q) {
		q = (q == null) ? "" : q.trim();
		if (q.isEmpty())
			return;

		List<String> latest = historyMgr.addAndSave(q);

        // 更新下拉模型：去重reset顶、最多100
		javax.swing.DefaultComboBoxModel<String> m = (javax.swing.DefaultComboBoxModel<String>) queryBox.getModel();
        // 简单粗暴：清空重加（100 项以内性能无感）
		m.removeAllElements();
		for (String s : latest)
			m.addElement(s);
		queryBox.setSelectedItem(q); // 缃《鏄剧ず
	}

    // 新增方法：
	private void installTablePopupActions() {
		rowPopup = new JPopupMenu();

		JMenuItem openItem = new JMenuItem("Open");
		JMenuItem revealItem = new JMenuItem("Reveal in Explorer");
		JMenu copyMenu = new JMenu("Copy");
		JMenuItem copyName = new JMenuItem("Name");
		JMenuItem copyPath = new JMenuItem("Full Path");
		copyMenu.add(copyName);
		copyMenu.add(copyPath);

        // --- Open With… 子菜单 ---
        JMenu openWith = new JMenu("Open With…");
        JMenuItem chooseProg = new JMenuItem("Choose Program…");
		openWith.add(chooseProg);
		openWith.addSeparator();
		rememberedOpenWithItem = new JMenuItem("(remembered)"); // 鐢ㄧ被瀛楁淇濆瓨
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
			RowSel s = getSelectedRow();
			if (s == null)
				return;
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
			RowSel s = getSelectedRow();
			if (s == null)
				return;
			try {
				java.awt.Desktop.getDesktop().open(new java.io.File(s.path));
			} catch (Exception ex) {
				JOptionPane.showMessageDialog(this, "Open failed:\n" + ex.getMessage());
			}
		});
		revealItem.addActionListener(e -> {
			RowSel s = getSelectedRow();
			if (s == null)
				return;
			try {
				revealInExplorer(s.path);
			} catch (Exception ex) {
				JOptionPane.showMessageDialog(this, "Reveal failed:\n" + ex.getMessage());
			}
		});
		copyName.addActionListener(e -> {
			RowSel s = getSelectedRow();
			if (s != null)
				setClipboard(s.name);
		});
		copyPath.addActionListener(e -> {
			RowSel s = getSelectedRow();
			if (s != null)
				setClipboard(s.path);
		});

        // 右键触发：按下/弹起都判断
		resultTable.addMouseListener(new java.awt.event.MouseAdapter() {
			@Override
			public void mousePressed(java.awt.event.MouseEvent e) {
				showPopup(e);
			}

			@Override
			public void mouseReleased(java.awt.event.MouseEvent e) {
				showPopup(e);
			}

			@Override
			public void mouseClicked(java.awt.event.MouseEvent e) {
				if (e.getClickCount() == 2 && e.getButton() == java.awt.event.MouseEvent.BUTTON1) {
					RowSel s = getSelectedRow();
					if (s == null)
						return;
					try {
						java.awt.Desktop.getDesktop().open(new java.io.File(s.path));
					} catch (Exception ignore) {
					}
				}
			}
		});
	}

    /** 右键菜单弹出，并动态刷新“记忆的程序”项 */
	private void showPopup(java.awt.event.MouseEvent e) {
		int r = resultTable.rowAtPoint(e.getPoint());
		if (r >= 0)
			resultTable.setRowSelectionInterval(r, r);
		if (!e.isPopupTrigger())
			return;

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
		if (r >= 0)
			resultTable.setRowSelectionInterval(r, r);
		if (e.isPopupTrigger()) {
			// 鍒锋柊 rememberedItem
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

	/** Export current table to CSV (UTF-8, with header, comma-separated, auto-quoted) */
	private void exportResultsToCsv() {
		if (model.getRowCount() == 0) {
			javax.swing.JOptionPane.showMessageDialog(this, "No rows to export.");
			return;
		}
		javax.swing.JFileChooser fc = new javax.swing.JFileChooser();
		fc.setDialogTitle("Export Results to CSV");
		fc.setSelectedFile(new java.io.File("docfinder-results.csv"));
		if (fc.showSaveDialog(this) != javax.swing.JFileChooser.APPROVE_OPTION)
			return;

		java.io.File out = fc.getSelectedFile();
		String sep = System.lineSeparator();

		try (java.io.PrintWriter pw = new java.io.PrintWriter(
				new java.io.OutputStreamWriter(new java.io.FileOutputStream(out), "UTF-8"))) {

			// Header
			int cols = model.getColumnCount();
			List<String> header = new java.util.ArrayList<>();
			for (int c = 0; c < cols; c++)
				header.add(csvQuote(model.getColumnName(c)));
			pw.write(String.join(",", header) + sep);

			// Data: current filtered results
			int rows = resultTable.getRowCount();
			for (int r = 0; r < rows; r++) {
				List<String> cells = new java.util.ArrayList<>();
				for (int c = 0; c < cols; c++) {
					Object val = resultTable.getValueAt(r, c);
					cells.add(csvQuote(val == null ? "" : val.toString()));
				}
				pw.write(String.join(",", cells) + sep);
			}
			pw.flush();
			statusLabel.setText("CSV exported: " + out.getAbsolutePath());
		} catch (Exception ex) {
			javax.swing.JOptionPane.showMessageDialog(this, "Export failed:\n" + ex.getMessage(), "Error",
					javax.swing.JOptionPane.ERROR_MESSAGE);
		}
	}

	private static String csvQuote(String s) {
		String t = s.replace("\"", "\"\"");
		return "\"" + t + "\"";
	}

	private static String getExtFromName(String name) {
		int i = name.lastIndexOf('.');
		return (i > 0) ? name.substring(i + 1).toLowerCase() : "";
	}

    // --- Open With 映射（openwith.<ext> -> program absolute path） ---
	private void openWithProgram(String programAbsPath, String fileAbsPath) {
		try {
            if (isMac()) {
                // macOS: open -a <App> <file> (当 programAbsPath 是 .app 或其内部二进制)
                new ProcessBuilder("open", "-a", programAbsPath, fileAbsPath).start();
            } else {
                // Windows / Linux: 直接执行 程序 + 文件
                new ProcessBuilder(programAbsPath, fileAbsPath).start();
            }
		} catch (Exception ex) {
			javax.swing.JOptionPane.showMessageDialog(this, "Open With failed:\n" + ex.getMessage());
		}
	}

	private static class RowSel { // 灏忓伐鍏风被
		final String name, path;

		RowSel(String n, String p) {
			name = n;
			path = p;
		}
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
        String html = "<html><body style='width:640px;font-family:sans-serif;font-size:12px;line-height:1.5'>"
                + "<h2>DocFinder - Usage Guide</h2>" +

                "<h3>Overview</h3>" + "<ul>" + "<li>Fast file-name search (prefix boosted).</li>"
                + "<li>Content search via Apache Tika (read-only parsing).</li>"
                + "<li>Better CJK (Chinese/Japanese) matching with specialized analyzers.</li>" + "</ul>" +

                "<h3>Quick Start</h3>" + "<ol>" + "<li>Open <b>File → Index Sources…</b> to add folders.</li>"
                + "<li>Run <b>File → Index All Sources</b> to build/update the index, or <b>Rebuild Index (Full)</b> to recreate it from scratch.</li>"
                + "<li>Type your query and press <b>Enter</b>.</li>" + "</ol>" +

                "<h3>Query Examples</h3>" + "<ul>" + "<li><code>report*</code> — prefix match on file name</li>"
                + "<li><code>\"project plan\"</code> — phrase match</li>"
                + "<li><code>content:kubernetes AND ingress</code> — content-only query</li>"
                + "<li><code>name:\"設計\"</code> / <code>content:\"設計 仕様\"</code> — Japanese examples</li>" + "</ul>" +

                "<h3>Filters</h3>" + "<ul>" + "<li>Click <b>Filters</b> to toggle filter bar.</li>"
                + "<li><b>Ext(s)</b>: comma-separated, e.g. <code>pdf,docx,txt</code>.</li>"
                + "<li><b>From / To</b>: date range (yyyy-MM-dd) for modified time.</li>" + "</ul>" +

                "<h3>Shortcuts & Actions</h3>" + "<ul>" + "<li><b>Ctrl+Alt+Space</b> — toggle main window</li>"
                + "<li><b>Enter</b> — run search / open selected file in results</li>"
                + "<li><b>Ctrl+C</b> — copy full path; <b>Ctrl+Shift+C</b> — copy file name</li>"
                + "<li><b>Alt+↓</b> — open query history dropdown</li>"
                + "<li><b>Ctrl+Shift+Delete</b> — Clear Search History…</li>"
                + "<li>Right-click a result row: <i>Open / Reveal in Explorer / Copy</i></li>" + "</ul>" +

                "<h3>Privacy & Safety</h3>" + "<ul>"
                + "<li>Indexing opens files in <b>read-only</b> mode. Contents and mtime are never modified by DocFinder.</li>"
                + "<li>On some NAS/SMB systems, <i>atime</i> (last access time) may be updated by the server when files are read.</li>"
                + "</ul>" +

                "<h3>Notes</h3>" + "<ul>" + "<li>Very large or encrypted files may have empty previews.</li>"
                + "<li>OCR for scanned PDFs/images is optional (currently disabled by default).</li>" + "</ul>"
                + "</body></html>";
        JEditorPane ep = new JEditorPane("text/html", html);
        ep.setEditable(false);
        JScrollPane sp = new JScrollPane(ep);
        sp.setPreferredSize(new Dimension(680, 460));
        JOptionPane.showMessageDialog(this, sp, "Usage Guide", JOptionPane.PLAIN_MESSAGE);
    }

    @Override
    public void onShowLogViewer() {
        if (logViewer.isVisible()) {
            logViewer.setVisible(false);
        } else {
            logViewer.setVisible(true);
        }
    }

    @Override
    public void onExit() {
        // Optional: gracefully stop local file watch & network polling (if currently enabled)
        try {
            if (liveWatchToggle != null && liveWatchToggle.isSelected()) {
                liveWatchToggle.setSelected(false);
                toggleLiveWatch();
            }
            if (netPollToggle != null && netPollToggle.isSelected()) {
                netPollToggle.setSelected(false);
                toggleNetPolling();
            }
        } catch (Exception ignore) {
        }

        // Optional: remove tray icon
        try {
            if (SystemTray.isSupported()) {
                SystemTray tray = SystemTray.getSystemTray();
                for (TrayIcon ti : tray.getTrayIcons())
                    tray.remove(ti);
            }
        } catch (Exception ignore) {
        }

        // Close window and exit (App has shutdown hook to unregister global hotkey)
        try {
            dispose();
        }
        catch (Exception ignore) {
        }
        System.exit(0);
    }

    @Override
    public void onIndexFolder() {
        chooseAndIndexFolder();
    }

    @Override
    public void onManageSources() {
        manageSources();
    }

    @Override
    public void onIndexAllSources() {
        indexAllSources();
    }

    @Override
    public void onShowIndexingSettings() {
        showIndexingSettings();
    }

    @Override
    public void onRebuildIndex() {
        rebuildAllSources();
    }

    @Override
    public void onExportResults() {
        exportResultsToCsv();
    }

    @Override
    public void onClearHistory() {
        clearSearchHistory();
    }

    @Override
    public void onToggleLiveWatch() {
        toggleLiveWatch();
    }

    @Override
    public void onToggleNetworkPolling() {
        toggleNetPolling();
    }

    @Override
    public void onPollNow() {
        pollOnceNow();
    }

    @Override
    public void onShowUsage() {
        showUsageDialog();
    }

	private static String fmtTime(long epochMs) {
		if (epochMs <= 0)
			return "";
		return new SimpleDateFormat().format(new java.util.Date(epochMs));
	}

	private FilterState buildFilterState() {
		FilterState f = new FilterState();
		f.exts = FilterState.parseExts(extField.getText());
		f.fromEpochMs = parseDateMs(fromField.getText());
		f.toEpochMs = parseDateMs(toField.getText());
		return f;
	}

	private Long parseDateMs(String s) {
		try {
			if (s == null || s.trim().isEmpty())
				return null;
			java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd");
			sdf.setLenient(false);
			return sdf.parse(s.trim()).getTime();
		} catch (Exception e) {
			return null;
		}
	}

	private static String fmtSize(long b) {
        // 简易人类可读：B / KB / MB / GB
		final long KB = 1024, MB = KB * 1024, GB = MB * 1024;
		if (b < KB)
			return b + " B";
		if (b < MB)
			return String.format("%.1f KB", b / (double) KB);
		if (b < GB)
			return String.format("%.1f MB", b / (double) MB);
		return String.format("%.1f GB", b / (double) GB);
	}

	private static boolean isWindows() {
		return System.getProperty("os.name").toLowerCase().contains("win");
	}

	private static boolean isMac() {
		return System.getProperty("os.name").toLowerCase().contains("mac");
	}

	public JTextField getSearchField() {
		return searchField;
	}

	public JTable getResultTable() {
		return resultTable;
	}

	public JLabel getStatusLabel() {
		return statusLabel;
	}

	public void setSearchService(SearchService newSvc) {
		if (this.searchService != null) {
			this.searchService.close(); // Close the old service
		}
		this.searchService = newSvc;
	}
	@Override
	public void dispose() {
		try {
			UIManager.removePropertyChangeListener(lafListener);
		} catch (Exception ignore) {
		}
		if (searchService != null) { // Close search service on dispose
			searchService.close();
		}
		searchExecutor.shutdownNow();
		super.dispose();
	}

}

