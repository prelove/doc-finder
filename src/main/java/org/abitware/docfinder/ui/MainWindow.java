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
import java.io.UnsupportedEncodingException;
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

public class MainWindow extends JFrame implements MenuBarPanel.MenuListener {
	private static final Logger log = LoggerFactory.getLogger(MainWindow.class);
	private org.abitware.docfinder.watch.LiveIndexService liveService;
	private javax.swing.JCheckBoxMenuItem liveWatchToggle;

	private javax.swing.JCheckBoxMenuItem netPollToggle;
	private org.abitware.docfinder.watch.NetPollerService netPoller;

	// Web server
	private org.abitware.docfinder.web.WebServer webServer;
	private javax.swing.JCheckBoxMenuItem webServerToggle;
	private javax.swing.JMenuItem openWebItem;

	// Score column toggle
	private javax.swing.JCheckBoxMenuItem showScoreToggle;
	/** Index of the Score column in the DefaultTableModel (fixed at 2). */
	private static final int SCORE_COL = 2;

	// ========= Fields =========
	// Safely published: written on the EDT (or during service reopen), read
	// once into a local by SearchWorker on the executor thread (D8).
	private volatile SearchService searchService;

	// Directory-scope selector (D5): committed snapshot read by doSearch.
	private final org.abitware.docfinder.ui.components.DirectoryScopePanel directoryScopePanel =
			new org.abitware.docfinder.ui.components.DirectoryScopePanel(
					this::onDirectoryScopeCommitted);

	// Monotonic guard for async service opens: window close / full rebuild /
	// a newer open request invalidates older candidates, which must then be
	// closed instead of published (D8).
	private volatile long openServiceSequence = 0L;
	private volatile long sourceLoadSequence = 0L;

	// Top bar: search and filters
	// Search box changed to editable combo box, editor is still JTextField
	private final javax.swing.JComboBox<SearchScope> scopeBox = new javax.swing.JComboBox<>(SearchScope.values());
	private final javax.swing.JComboBox<MatchMode> matchModeBox = new javax.swing.JComboBox<>(MatchMode.values());
	private final javax.swing.JComboBox<String> queryBox = new javax.swing.JComboBox<>();
	private javax.swing.JTextField searchField; // Actual editor
	private boolean suppressQueryActions = false;
	private final org.abitware.docfinder.search.SearchHistoryManager historyMgr = new org.abitware.docfinder.search.SearchHistoryManager();

	// Popup & "Open With" remembered item (for right-click menu and refresh)
	private JPopupMenu rowPopup;
	private JMenuItem rememberedOpenWithItem;
	private JMenuItem searchInThisFolderItem;
	private JMenuItem searchInContainingItem;

	private final JTextField extField = new JTextField(); // Comma-separated extensions
	private JFormattedTextField fromField; // yyyy-MM-dd
	private JFormattedTextField toField; // yyyy-MM-dd
	private final JButton toggleFiltersBtn = new JButton("Filters");
	private final JButton applyFiltersBtn = new JButton("Apply");
	private final JPanel filterBar = new JPanel(new BorderLayout(6, 6)); // Collapsible filter bar

	// Center: results + preview
	private final DefaultTableModel model = new DefaultTableModel(
			new Object[] { "Name", "Path", "Score", "Size", "Created", "Accessed", "Match" }, 0) {
		@Override
		public boolean isCellEditable(int r, int c) {
			return false;
		}

		@Override
		public Class<?> getColumnClass(int columnIndex) {
			if (columnIndex == 2) {
				return Float.class;
			}
			if (columnIndex == 3) {
				return Long.class;
			}
			return String.class;
		}
	};

	private final JTable resultTable = new JTable(model);
	private final JEditorPane preview = new JEditorPane("text/html", "");
	private String lastPreviewInner = null;
	private final PropertyChangeListener lafListener;
	private JSplitPane split;

	// Bottom: status bar
	private final JLabel statusLabel = new JLabel("Search index is initializing...");
	private final JLabel progressLabel = new JLabel(" ");
	private final JProgressBar progressBar = new JProgressBar();
	private final JToggleButton previewToggle = new JToggleButton("Preview");
	private int lastDividerLocation = -1;

	// Preview/search context
	private String lastQuery = "";
	private javax.swing.SwingWorker<String, Void> activePreviewWorker;
	private long previewSequence = 0L;
	private String cachedPreviewPath;
	private String cachedPreviewQuery;
	private String cachedPreviewHtml;
	private java.util.List<SearchResult> currentResults = java.util.Collections.emptyList();

	private static class PreviewLoadOutcome {
		final String text;
		final String reason;

		PreviewLoadOutcome(String text, String reason) {
			this.text = text;
			this.reason = reason;
		}
	}

	private SearchWorker activeSearchWorker;
	private long searchSequence = 0L;
	private volatile boolean isIndexing = false;
	private final ExecutorService searchExecutor = Executors.newSingleThreadExecutor();

	// Debounce (D6): class-A typing immediately invalidates in-flight work and
	// restarts this single 250ms timer; class-B submissions stop it and submit
	// once; class-C programmatic writes never reach the document listener.
	private javax.swing.Timer searchDebounceTimer;
	private boolean programmaticQueryEdit = false;

	// Editable JComboBox can deliver one Enter commit as two ActionEvents when
	// the typed text is not yet in the model (verified on a real Swing field:
	// first Enter on a new keyword submitted twice, later Enters once). Drop a
	// duplicate event carrying the same text within a single gesture (D6 B).
	// Editable-combo duplicate events are filtered by event semantics in the
	// action listener (comboBoxEdited vs comboBoxChanged), not by time windows.

	// ========= Constructor =========
	public MainWindow(SearchService searchService) {
		super("DocFinder");
		setSearchService(searchService); // Use the setter to manage lifecycle

		setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
		setMinimumSize(new Dimension(900, 560));
		setLocationRelativeTo(null);
		getContentPane().setLayout(new BorderLayout());

		// 1) Top North: search bar + directory scope + collapsible filter bar
		JPanel north = new JPanel(new BorderLayout());
		north.add(buildTopBar(), BorderLayout.NORTH);
		JPanel northCenter = new JPanel(new BorderLayout());
		northCenter.add(directoryScopePanel, BorderLayout.NORTH);
		northCenter.add(buildFilterBar(), BorderLayout.CENTER); // Hidden by default
		north.add(northCenter, BorderLayout.CENTER);
		getContentPane().add(north, BorderLayout.NORTH);

		// 2) Center: results table + right preview panel
		getContentPane().add(buildCenterAndPreview(), BorderLayout.CENTER);

		// 3) Bottom South: status bar
		getContentPane().add(buildStatusBar(), BorderLayout.SOUTH);

		// 4) Menu bar (File / Help)
		MenuBarPanel menuBar = buildMenuBar();
		menuBar.setMenuListener(this);
		setJMenuBar(menuBar);

		// Wire toggle items from the menu bar so toggleLiveWatch()/toggleNetPolling()
		// can read and update their checked state without NPE.
		this.liveWatchToggle = menuBar.getLiveWatchToggle();
		this.netPollToggle = menuBar.getNetPollToggle();
		this.webServerToggle = menuBar.getWebServerToggle();
		this.openWebItem = menuBar.getOpenWebItem();
		this.showScoreToggle = menuBar.getShowScoreToggle();

		// Restore web server toggle state from config
		org.abitware.docfinder.index.ConfigManager cfgInit = new org.abitware.docfinder.index.ConfigManager();
		if (webServerToggle != null) webServerToggle.setSelected(cfgInit.isWebEnabled());
		// Restore score column visibility from config (default hidden)
		boolean showScore = cfgInit.isShowScoreColumn();
		if (showScoreToggle != null) showScoreToggle.setSelected(showScore);
		setScoreColumnVisible(showScore);

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

		// Load configured sources for the directory-scope combo off the EDT
		refreshSourceDirectoriesAsync();

		lafListener = evt -> {
			if ("lookAndFeel".equals(evt.getPropertyName())) {
				javax.swing.SwingUtilities.invokeLater(this::refreshPreviewForTheme);
			}
		};
		UIManager.addPropertyChangeListener(lafListener);

	}

	/** Top search bar (includes Filters button) */
	private JComponent buildTopBar() {
		JPanel top = new JPanel(new BorderLayout(6, 0));
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
		searchField = getActiveSearchField();
		searchField.putClientProperty("JTextField.placeholderText",
				"Search... (e.g. report*, content:\"zero knowledge\", name:\"检查清单\")");

		// Debounce: a single 250ms non-repeating timer that only delays the
		// submit; user typing invalidates in-flight work immediately (D6 A).
		searchDebounceTimer = new javax.swing.Timer(250, e -> doSearch());
		searchDebounceTimer.setRepeats(false);
		searchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
			@Override public void insertUpdate(javax.swing.event.DocumentEvent e) { onUserTyping(); }
			@Override public void removeUpdate(javax.swing.event.DocumentEvent e) { onUserTyping(); }
			@Override public void changedUpdate(javax.swing.event.DocumentEvent e) { onUserTyping(); }
		});

		// History dropdown selection + editor Enter both go through combo action.
		// An Enter commit on text that is new to the model fires two events
		// (comboBoxChanged then comboBoxEdited); Enter on an already-selected
		// value fires only comboBoxEdited. Dedup by event semantics: the edit
		// commit is the Enter signal, and comboBoxChanged only counts when it is
		// a real dropdown pick (popup open, selection is a model entry).
		queryBox.addActionListener(e -> {
			if (suppressQueryActions)
				return;
			String command = e.getActionCommand();
			if ("comboBoxEdited".equals(command)) {
				String text = getQueryText();
				if (!text.isEmpty()) {
					doSearch();
				}
				return;
			}
			Object sel = queryBox.getSelectedItem();
			if ("comboBoxChanged".equals(command) && sel != null && queryBox.isPopupVisible()
					&& queryBox.getSelectedIndex() >= 0) {
				setQueryText(sel.toString());
				if (!getQueryText().isEmpty()) {
					doSearch();
				}
			}
		});

		// Load and add history items to query box; suppress actions so a
		// non-empty history cannot fire doSearch before the filter bar exists.
		suppressQueryActions = true;
		List<String> hist = historyMgr.load();
		for (String s : hist)
			queryBox.addItem(s);

		// Shortcuts: focus search field, clear selection
		queryBox.setSelectedItem(""); // <-- Clear
		suppressQueryActions = false;
		searchField.requestFocusInWindow(); // Focus search field

		toggleFiltersBtn.addActionListener(e -> filterBar.setVisible(!filterBar.isVisible()));

		scopeBox.addActionListener(e -> rerunIfQueryPresent());
		matchModeBox.addActionListener(e -> rerunIfQueryPresent());

		JLabel scopeLabel = new JLabel("Scope:");
		JPanel leftStrip = new JPanel(new BorderLayout(6, 0));
		leftStrip.add(scopeLabel, BorderLayout.WEST);
		leftStrip.add(scopeBox, BorderLayout.CENTER);

		JPanel eastStrip = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
		eastStrip.add(matchModeBox);
		eastStrip.add(toggleFiltersBtn);
		top.add(leftStrip, BorderLayout.WEST);
		top.add(queryBox, BorderLayout.CENTER);
		top.add(eastStrip, BorderLayout.EAST);

		// Share one label column, combo width and east width with the "Search in" row.
		int labelW = Math.max(scopeLabel.getPreferredSize().width, directoryScopePanel.getLabelPreferredWidth());
		int comboW = Math.max(scopeBox.getPreferredSize().width, 170);
		int eastW = Math.max(eastStrip.getPreferredSize().width, directoryScopePanel.getEastPreferredWidth());
		eastStrip.setPreferredSize(new Dimension(eastW, eastStrip.getPreferredSize().height));
		scopeLabel.setPreferredSize(new Dimension(labelW, scopeLabel.getPreferredSize().height));
		scopeBox.setPreferredSize(new Dimension(comboW, scopeBox.getPreferredSize().height));
		directoryScopePanel.alignWith(labelW, comboW, eastW);
		return top;
	}


	private String getQueryText() {
		javax.swing.JTextField tf = getActiveSearchField();
		return (tf == null) ? "" : tf.getText().trim();
	}

	private void rerunIfQueryPresent() {
		String text = getQueryText();
		if (!text.isEmpty()) {
			doSearch();
		}
	}

	/**
	 * Programmatic write (class C): updates the control only — never submits,
	 * never starts debounce, never touches in-flight state (D6).
	 */
	private void setQueryText(String s) {
		javax.swing.JTextField tf = getActiveSearchField();
		if (tf == null)
			return;
		programmaticQueryEdit = true;
		try {
			tf.setText(s);
		} finally {
			programmaticQueryEdit = false;
		}
	}

	/** Class A: real user edit — in-flight request dies now; submit is delayed. */
	private void onUserTyping() {
		if (programmaticQueryEdit || suppressQueryActions)
			return;
		searchSequence++;
		if (activeSearchWorker != null) {
			activeSearchWorker.cancel(true);
			activeSearchWorker = null;
		}
		if (searchDebounceTimer != null) {
			searchDebounceTimer.restart();
		}
	}

	private void stopSearchDebounce() {
		if (searchDebounceTimer != null && searchDebounceTimer.isRunning()) {
			searchDebounceTimer.stop();
		}
	}

	private javax.swing.JTextField getActiveSearchField() {
		java.awt.Component c = queryBox.getEditor().getEditorComponent();
		if (c instanceof javax.swing.JTextField) {
			searchField = (javax.swing.JTextField) c;
		}
		return searchField;
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

		applyFiltersBtn.addActionListener(e -> doSearch());
		row.add(applyFiltersBtn);

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
		resultTable.getColumnModel().getColumn(2).setPreferredWidth(70);  // Score
		resultTable.getColumnModel().getColumn(3).setPreferredWidth(90);  // Size
		resultTable.getColumnModel().getColumn(4).setPreferredWidth(130); // Created
		resultTable.getColumnModel().getColumn(5).setPreferredWidth(130); // Accessed
		resultTable.getColumnModel().getColumn(6).setPreferredWidth(110); // Match

		javax.swing.table.DefaultTableCellRenderer scoreRenderer = new javax.swing.table.DefaultTableCellRenderer() {
			@Override
			public void setValue(Object value) {
				if (value instanceof Number) {
					super.setValue(String.format("%.3f", ((Number) value).floatValue()));
				} else {
					super.setValue(value);
				}
			}
		};
		scoreRenderer.setHorizontalAlignment(javax.swing.SwingConstants.RIGHT);
		resultTable.getColumnModel().getColumn(2).setCellRenderer(scoreRenderer);

		javax.swing.table.DefaultTableCellRenderer sizeRenderer = new javax.swing.table.DefaultTableCellRenderer() {
			@Override
			public void setValue(Object value) {
				if (value instanceof Number) {
					long bytes = ((Number) value).longValue();
					super.setValue(fmtSize(bytes));
				} else {
					super.setValue(value);
				}
			}
		};
		sizeRenderer.setHorizontalAlignment(javax.swing.SwingConstants.RIGHT);
		resultTable.getColumnModel().getColumn(3).setCellRenderer(sizeRenderer);

		JScrollPane center = new JScrollPane(resultTable);

		// Preview pane: HTML, non-editable
		preview.setEditable(false);
		JScrollPane right = new JScrollPane(preview);
		right.setPreferredSize(new Dimension(360, 560));

		split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, center, right);
		split.setResizeWeight(0.75); // Left 75% / Right 25%
		split.setOneTouchExpandable(true);
		javax.swing.SwingUtilities.invokeLater(() -> split.setDividerLocation(0.75));
		return split;
	}

	/** Bottom status bar */
	private JComponent buildStatusBar() {
		JPanel bottom = new JPanel(new BorderLayout(8, 0));
		bottom.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
		progressBar.setIndeterminate(false);
		progressBar.setVisible(false);

		previewToggle.setText("👁");
		previewToggle.setToolTipText("Toggle preview panel");
		previewToggle.setSelected(true);
		previewToggle.addActionListener(e -> setPreviewVisible(previewToggle.isSelected()));

		JPanel east = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
		east.add(previewToggle);
		east.add(progressBar);
		bottom.add(statusLabel, BorderLayout.WEST);
		bottom.add(progressLabel, BorderLayout.CENTER);
		bottom.add(east, BorderLayout.EAST);
		return bottom;
	}

	private void setPreviewVisible(boolean visible) {
		if (split == null) return;
		if (visible) {
			if (lastDividerLocation > 0) {
				split.setDividerLocation(lastDividerLocation);
			} else {
				split.setDividerLocation(0.75);
			}
			split.getRightComponent().setVisible(true);
		} else {
			lastDividerLocation = split.getDividerLocation();
			split.getRightComponent().setVisible(false);
			split.setDividerLocation(1.0);
		}
		split.revalidate();
	}

	private void setBusyProgress(boolean busy, String text) {
		progressBar.setVisible(busy);
		progressBar.setIndeterminate(busy);
		progressLabel.setText((text == null || text.trim().isEmpty()) ? " " : text);
	}

	private void setUiBusyCursor(boolean busy) {
		java.awt.Cursor c = busy
				? java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.WAIT_CURSOR)
				: java.awt.Cursor.getDefaultCursor();
		setCursor(c);
		if (getRootPane() != null) {
			getRootPane().setCursor(c);
		}
		if (getGlassPane() != null) {
			getGlassPane().setCursor(c);
			getGlassPane().setVisible(busy);
		}
	}

	private void setIndexingUiEnabled(boolean enabled) {
		searchField.setEnabled(enabled);
		queryBox.setEnabled(enabled);
		scopeBox.setEnabled(enabled);
		matchModeBox.setEnabled(enabled);
		directoryScopePanel.setScopeControlsEnabled(enabled);
		toggleFiltersBtn.setEnabled(enabled);
		applyFiltersBtn.setEnabled(enabled);
		extField.setEnabled(enabled);
		fromField.setEnabled(enabled);
		toField.setEnabled(enabled);
		if (webServerToggle != null) webServerToggle.setEnabled(enabled);
	}

	private JLabel buildHintLabel(String text) {
		JLabel label = new JLabel("<html><span style='color:#666666;'>" + text + "</span></html>");
		label.setFont(label.getFont().deriveFont(11f));
		return label;
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
						org.abitware.docfinder.watch.NetPollerService poller = new org.abitware.docfinder.watch.NetPollerService(sm.getIndexDir(), s, netRoots, MainWindow.this::onIndexCommit);
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

		setUiBusyCursor(true);
		setBusyProgress(true, "Polling network sources...");
		statusLabel.setText("Polling network sources...");

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
					setBusyProgress(false, "");
					setUiBusyCursor(false);
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
							message = "No local sources. Use 'Manage Sources...' first.";
							messageType = javax.swing.JOptionPane.INFORMATION_MESSAGE;
							return null;
						}
						org.abitware.docfinder.index.ConfigManager cm = new org.abitware.docfinder.index.ConfigManager();
						org.abitware.docfinder.index.IndexSettings s = cm.loadIndexSettings();
						org.abitware.docfinder.watch.LiveIndexService existing = liveService;
						if (existing != null) {
							try { existing.close(); } catch (Exception ignore) {}
						}
						org.abitware.docfinder.watch.LiveIndexService service = new org.abitware.docfinder.watch.LiveIndexService(sm.getIndexDir(), s, localRoots, MainWindow.this::onIndexCommit);
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

			// 3) Clear current input (also drops any pending debounced search)
			stopSearchDebounce();
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
		if (isIndexing) {
			JOptionPane.showMessageDialog(this, "An indexing operation is already in progress.");
			return;
		}

		org.abitware.docfinder.index.SourceManager sm = new org.abitware.docfinder.index.SourceManager();
		java.util.List<java.nio.file.Path> sources = sm.load();
		if (sources.isEmpty()) {
			JOptionPane.showMessageDialog(this, "No sources configured. Use 'Manage Sources...' first.");
			return;
		}

		long t0 = System.currentTimeMillis();
        statusLabel.setText("Indexing all sources…");
		setBusyProgress(true, "Building index...");

		java.nio.file.Path indexDir = sm.getIndexDir();
		isIndexing = true;
		setIndexingUiEnabled(false);
		stopSearchDebounce();
		searchSequence++;
		if (activeSearchWorker != null) {
			activeSearchWorker.cancel(true);
			activeSearchWorker = null;
		}

		new javax.swing.SwingWorker<Integer, Void>() {
			@Override
			protected Integer doInBackground() throws Exception {
				org.abitware.docfinder.index.ConfigManager cm = new org.abitware.docfinder.index.ConfigManager();
				org.abitware.docfinder.index.IndexSettings s = cm.loadIndexSettings();
				try (org.abitware.docfinder.index.LuceneIndexer idx = new org.abitware.docfinder.index.LuceneIndexer(
						indexDir, s)) {
					int total = 0;
					for (java.nio.file.Path p : sources) {
						total += idx.indexFolder(p);
					}
					return total;
				}
			}

			@Override
			protected void done() {
				boolean ok = false;
				try {
					int n = get();
					long ms = System.currentTimeMillis() - t0;
					statusLabel.setText("Indexed files: " + n + " | Time: " + ms + " ms | Index: " + indexDir);
					ok = true;
				} catch (Exception ex) {
					statusLabel.setText("Indexing failed or partially completed."
							+ " Search uses available committed data. See the application log.");
					log.warn("Index All Sources failed or partially completed: {}", String.valueOf(ex.getCause() != null ? ex.getCause() : ex));
				} finally {
					isIndexing = false;
					setIndexingUiEnabled(true);
					setBusyProgress(false, "");
					// Whether the run fully succeeded or stopped after partial
					// commits, try to (re)open the search service so committed
					// data becomes searchable without restart (D8/F-022). If no
					// valid commit exists the open fails → INDEX_UNAVAILABLE.
					org.abitware.docfinder.index.IndexSettings svc_s =
							new org.abitware.docfinder.index.ConfigManager().loadIndexSettings();
					reopenSearchServiceAsync(indexDir, svc_s, ok ? null
							: "Indexing failed or partially completed."
							+ " Search uses available committed data. See the application log.");
					refreshSourceDirectoriesAsync();
				}
			}
		}.execute();
	}

    // 用 Tika 只读抽取的轻量预览（在 EDT 之外跑）
	private void loadPreviewAsync() {
		cancelActivePreviewWorker();

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
		final String currentQuery = (lastQuery == null) ? "" : lastQuery.trim();
		final String pathText = path.toString();
		if (java.util.Objects.equals(cachedPreviewPath, pathText)
				&& java.util.Objects.equals(cachedPreviewQuery, currentQuery)
				&& cachedPreviewHtml != null) {
			updatePreviewInner(cachedPreviewHtml, false);
			return;
		}

		final long token = ++previewSequence;

		if (java.nio.file.Files.isDirectory(path)) {
			updatePreviewInner("Loading folder...", false);
			final java.nio.file.Path dir = path;
			javax.swing.SwingWorker<String, Void> worker = new javax.swing.SwingWorker<String, Void>() {
				@Override
				protected String doInBackground() {
					return buildFolderPreviewHtml(dir, 200);
				}

				@Override
				protected void done() {
					if (isCancelled() || token != previewSequence || isPreviewSelectionChanged(pathText)) {
						return;
					}
					try {
						String html = get();
						cachedPreviewPath = pathText;
						cachedPreviewQuery = currentQuery;
						cachedPreviewHtml = html;
						updatePreviewInner(html);
				} catch (Exception ex) {
						updatePreviewInner("Preview failed.");
					}
				}
			};
			activePreviewWorker = worker;
			worker.execute();
			return;
		}
		if (!java.nio.file.Files.isRegularFile(path)) {
			updatePreviewInner("File not found.");
			return;
		}
		updatePreviewInner("Loading preview...", false);
		final java.nio.file.Path target = path;
		javax.swing.SwingWorker<String, Void> worker = new javax.swing.SwingWorker<String, Void>() {
			@Override
			protected String doInBackground() throws Exception {
				final int MAX_CHARS = 60_000;
				PreviewLoadOutcome outcome = loadPreviewText(target, MAX_CHARS);
				String text = (outcome == null) ? "" : outcome.text;
				if (text == null || text.trim().isEmpty()) {
					String reason = (outcome == null || outcome.reason == null)
							? "No text content."
							: outcome.reason;
					return "(" + reason + ")";
				}

				String q = (currentQuery == null) ? "" : currentQuery.trim();
				String[] terms = tokenizeForHighlight(q);
				String snippet = makeSnippet(text, terms, 300);
				String html = toHtml(snippet, terms);
				return html;
			}

			@Override
			protected void done() {
				if (isCancelled() || token != previewSequence || isPreviewSelectionChanged(pathText)) {
					return;
				}
				try {
					String html = get();
					cachedPreviewPath = pathText;
					cachedPreviewQuery = currentQuery;
					cachedPreviewHtml = html;
					updatePreviewInner(html, false);
				} catch (Exception ex) {
					updatePreviewInner("Preview failed.");
				}
			}
		};
		activePreviewWorker = worker;
		worker.execute();
	}

	private void cancelActivePreviewWorker() {
		if (activePreviewWorker != null) {
			activePreviewWorker.cancel(true);
			activePreviewWorker = null;
		}
	}

	private boolean isPreviewSelectionChanged(String expectedPath) {
		RowSel current = getSelectedRow();
		return current == null || !expectedPath.equals(current.path);
	}


    // 只读抽取前 N 字符（复用我们已有的 Tika 逻辑，简化为局部方法以免循环依赖）
	private PreviewLoadOutcome extractTextHead(java.nio.file.Path file, int maxChars) {
		int timeoutSec = new org.abitware.docfinder.index.ConfigManager().loadIndexSettings().previewTimeoutSec;
		java.util.concurrent.ExecutorService es = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, "docfinder-preview-parse");
			t.setDaemon(true);
			return t;
		});
		try {
			java.util.concurrent.Future<String> fut = es.submit(() -> {
				try (java.io.InputStream is = java.nio.file.Files.newInputStream(file, java.nio.file.StandardOpenOption.READ)) {
					org.apache.tika.metadata.Metadata md = new org.apache.tika.metadata.Metadata();
					md.set(org.apache.tika.metadata.TikaCoreProperties.RESOURCE_NAME_KEY, file.getFileName().toString());
					org.apache.tika.parser.AutoDetectParser parser = new org.apache.tika.parser.AutoDetectParser();
					org.apache.tika.sax.BodyContentHandler handler = new org.apache.tika.sax.BodyContentHandler(maxChars);
					org.apache.tika.parser.ParseContext ctx = new org.apache.tika.parser.ParseContext();
					parser.parse(is, handler, md, ctx);
					return handler.toString();
				}
			});
			String text = fut.get(timeoutSec, java.util.concurrent.TimeUnit.SECONDS);
			return new PreviewLoadOutcome(text, null);
		} catch (java.util.concurrent.TimeoutException e) {
			return new PreviewLoadOutcome("", "Preview parse timed out.");
		} catch (Throwable e) {
			return new PreviewLoadOutcome("", null);
		} finally {
			es.shutdownNow();
		}
	}

	private PreviewLoadOutcome loadPreviewText(java.nio.file.Path file, int maxChars) {
		if (file == null || maxChars <= 0) {
			return new PreviewLoadOutcome("", "Preview unavailable.");
		}
		PreviewLoadOutcome tikaOutcome = extractTextHead(file, maxChars);
		String viaTika = tikaOutcome == null ? "" : tikaOutcome.text;
		if (viaTika != null && !viaTika.trim().isEmpty()) {
			return new PreviewLoadOutcome(viaTika, null);
		}

		if (isLikelyBinary(file)) {
			return new PreviewLoadOutcome("", "Binary or non-text file.");
		}

		String fallback = readTextFallback(file, maxChars);
		if (fallback != null && !fallback.trim().isEmpty()) {
			return new PreviewLoadOutcome(fallback, null);
		}

		if (tikaOutcome != null && tikaOutcome.reason != null) {
			return tikaOutcome;
		}
		return new PreviewLoadOutcome("", "Unsupported or empty text content.");
	}

	private boolean isLikelyBinary(java.nio.file.Path file) {
		byte[] buf = new byte[4096];
		try (java.io.InputStream is = java.nio.file.Files.newInputStream(file, java.nio.file.StandardOpenOption.READ)) {
			int n = is.read(buf);
			if (n <= 0) return false;
			int printable = 0;
			for (int i = 0; i < n; i++) {
				int b = buf[i] & 0xFF;
				if (b == 0x00) return true;
				if (b == 0x09 || b == 0x0A || b == 0x0D || (b >= 0x20 && b <= 0x7E)) {
					printable++;
				}
			}
			double ratio = printable / (double) n;
			return ratio < 0.2;
		} catch (Exception ignore) {
			return false;
		}
	}

	private String readTextFallback(java.nio.file.Path file, int maxChars) {
		java.util.List<java.nio.charset.Charset> candidates = detectCandidateCharsets(file);
		String bestText = "";
		double bestScore = -1d;
		for (java.nio.charset.Charset cs : candidates) {
			if (cs == null) {
				continue;
			}
			try {
				String text = readTextWithCharset(file, maxChars, cs);
				if (text == null || text.trim().isEmpty()) {
					continue;
				}
				double score = scorePreviewText(text);
				if (score > bestScore) {
					bestScore = score;
					bestText = text;
				}
			} catch (Exception ignore) {
			}
		}
		return bestText;
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

	private java.util.List<java.nio.charset.Charset> detectCandidateCharsets(java.nio.file.Path file) {
		java.util.LinkedHashSet<java.nio.charset.Charset> candidates = new java.util.LinkedHashSet<>();
		java.nio.charset.Charset bom = detectBomCharset(file);
		if (bom != null) {
			candidates.add(bom);
		}
		try (java.io.InputStream is = java.nio.file.Files.newInputStream(file, java.nio.file.StandardOpenOption.READ)) {
			CharsetDetector detector = new CharsetDetector();
			detector.setText(is);
			CharsetMatch[] matches = detector.detectAll();
			if (matches != null) {
				for (CharsetMatch match : matches) {
					if (match == null || match.getConfidence() < 30) {
						continue;
					}
					try {
						candidates.add(java.nio.charset.Charset.forName(match.getName()));
					} catch (Exception ignore) {
					}
				}
			}
		} catch (Exception ignore) {
		}

		candidates.add(java.nio.charset.StandardCharsets.UTF_8);
		candidates.add(java.nio.charset.StandardCharsets.UTF_16LE);
		candidates.add(java.nio.charset.StandardCharsets.UTF_16BE);
		addCharsetIfSupported(candidates, "windows-1252");
		addCharsetIfSupported(candidates, "Shift_JIS");
		addCharsetIfSupported(candidates, "Windows-31J");
		addCharsetIfSupported(candidates, "EUC-JP");
		addCharsetIfSupported(candidates, "GBK");
		addCharsetIfSupported(candidates, "Big5");
		addCharsetIfSupported(candidates, "EUC-KR");
		return new java.util.ArrayList<>(candidates);
	}

	private void addCharsetIfSupported(java.util.Set<java.nio.charset.Charset> candidates, String name) {
		try {
			candidates.add(java.nio.charset.Charset.forName(name));
		} catch (Exception ignore) {
		}
	}

	private double scorePreviewText(String text) {
		if (text == null || text.isEmpty()) {
			return 0d;
		}
		int length = text.length();
		int replacement = 0;
		int printable = 0;
		for (int i = 0; i < length; i++) {
			char ch = text.charAt(i);
			if (ch == '\uFFFD') {
				replacement++;
				continue;
			}
			if (ch == '\n' || ch == '\r' || ch == '\t') {
				printable++;
				continue;
			}
			if (ch >= 0x20) {
				printable++;
			}
		}
		double replacementRatio = replacement / (double) length;
		double printableRatio = printable / (double) length;
		return (1d - replacementRatio) * 0.7d + printableRatio * 0.3d;
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
		int viewRow = resultTable.getSelectedRow();
		if (viewRow < 0)
			return null;
		int modelRow = resultTable.convertRowIndexToModel(viewRow);
		String name = String.valueOf(model.getValueAt(modelRow, 0));
		String path = String.valueOf(model.getValueAt(modelRow, 1));
		return new RowSel(name, path, modelRow);
	}

	private SearchResult getSelectedResult() {
		RowSel s = getSelectedRow();
		if (s == null || s.modelRow < 0 || s.modelRow >= currentResults.size()) {
			return null;
		}
		return currentResults.get(s.modelRow);
	}

	private void chooseAndIndexFolder() {
		if (isIndexing) {
			JOptionPane.showMessageDialog(this, "An indexing operation is already in progress.");
			return;
		}

		JFileChooser fc = new JFileChooser();
		fc.setDialogTitle("Choose a folder to index");
		fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
		if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION)
			return;

		java.io.File folder = fc.getSelectedFile();
		statusLabel.setText("Indexing: " + folder.getAbsolutePath() + " ...");
		setBusyProgress(true, "Indexing selected folder...");

        // 索引目录：用户主目录下 .docfinder/index
        java.nio.file.Path indexDir = new org.abitware.docfinder.index.ConfigManager().getIndexDir();

		new javax.swing.SwingWorker<Integer, Void>() {
			@Override
			protected Integer doInBackground() throws Exception {
				org.abitware.docfinder.index.ConfigManager cm = new org.abitware.docfinder.index.ConfigManager();
				org.abitware.docfinder.index.IndexSettings s = cm.loadIndexSettings();
				try (org.abitware.docfinder.index.LuceneIndexer idx = new org.abitware.docfinder.index.LuceneIndexer(
						indexDir, s)) {
					return idx.indexFolder(folder.toPath());
				}
			}

			@Override
			protected void done() {
				try {
					int n = get();
					statusLabel.setText("Indexed files: " + n + "  |  Index: " + indexDir.toString());
                    // 切换到 Lucene 搜索服务（后台打开，EDT 统一发布 — D8）
					org.abitware.docfinder.index.IndexSettings svc_s = new org.abitware.docfinder.index.ConfigManager().loadIndexSettings();
					reopenSearchServiceAsync(indexDir, svc_s);
                    // 可选：自动触发一次搜索以验证
                    // doSearch();
				} catch (Exception ex) {
					statusLabel.setText("Index failed: " + ex.getMessage());
				} finally {
					setBusyProgress(false, "");
					refreshSourceDirectoriesAsync();
				}
			}
		}.execute();
	}

	private void showIndexingSettings() {
		org.abitware.docfinder.index.ConfigManager cm = new org.abitware.docfinder.index.ConfigManager();
		org.abitware.docfinder.index.IndexSettings s = cm.loadIndexSettings();

		JSpinner maxMb = new JSpinner(new SpinnerNumberModel((int) s.maxFileMB, 1, 1024, 1));
		JSpinner timeout = new JSpinner(new SpinnerNumberModel(s.parseTimeoutSec, 1, 120, 1));
		JSpinner previewTimeout = new JSpinner(new SpinnerNumberModel(s.previewTimeoutSec, 1, 60, 1));
		JSpinner maxExtractChars = new JSpinner(new SpinnerNumberModel(s.maxExtractChars, 100000, 20000000, 100000));
		JTextField include = new JTextField(String.join(",", s.includeExt));
		JTextArea exclude = new JTextArea(String.join(";", s.excludeGlob));
		JTextField indexDirField = new JTextField(cm.getIndexDir().toString());
		indexDirField.setEditable(false);
		JButton browseIndexDir = new JButton("Browse...");
		browseIndexDir.addActionListener(e -> {
			JFileChooser chooser = new JFileChooser(indexDirField.getText());
			chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
			chooser.setDialogTitle("Choose index storage folder");
			if (chooser.showOpenDialog(MainWindow.this) == JFileChooser.APPROVE_OPTION) {
				indexDirField.setText(chooser.getSelectedFile().toPath().toAbsolutePath().normalize().toString());
			}
		});
		JPanel indexDirRow = new JPanel(new BorderLayout(6, 0));
		indexDirRow.add(indexDirField, BorderLayout.CENTER);
		indexDirRow.add(browseIndexDir, BorderLayout.EAST);

		exclude.setRows(3);
		include.setToolTipText("Example: pdf,docx,xlsx,txt");
		exclude.setToolTipText("Example: **/node_modules/**;**/$RECYCLE.BIN/**;**/System Volume Information/**");

		JLabel maxHint = buildHintLabel("Files larger than this are skipped for content parsing.");
		JLabel timeoutHint = buildHintLabel("Per-file parsing timeout (seconds).");
		JLabel previewTimeoutHint = buildHintLabel("Preview pane parsing timeout (seconds).");
		JLabel maxExtractHint = buildHintLabel("Maximum characters extracted per file (index.maxExtractChars). Raise to 2000000+ for large text/code files.");
		JLabel includeHint = buildHintLabel("Comma-separated list of extensions to parse with full content extraction.");
		JLabel excludeHint = buildHintLabel("Semicolon-separated glob patterns to skip during indexing.");
		JLabel indexDirHint = buildHintLabel("Default is ./.docfinder/index (next to app). You can move it to shared/network folders.");

		JPanel p = new JPanel(new java.awt.GridBagLayout());
		java.awt.GridBagConstraints c = new java.awt.GridBagConstraints();
		c.insets = new java.awt.Insets(4, 4, 4, 4);
		c.fill = java.awt.GridBagConstraints.HORIZONTAL;
		c.weightx = 1;
		int r = 0;
		c.gridx = 0;
		c.gridy = r;
		p.add(new JLabel("Index location:"), c);
		c.gridx = 1;
		p.add(indexDirRow, c);
		r++;
		c.gridx = 0;
		c.gridy = r;
		c.gridwidth = 2;
		p.add(indexDirHint, c);
		r++;
		c.gridwidth = 1;
		c.gridx = 0;
		c.gridy = r;
		p.add(new JLabel("Max file size (MB):"), c);
		c.gridx = 1;
		p.add(maxMb, c);
		r++;
		c.gridx = 0;
		c.gridy = r;
		c.gridwidth = 2;
		p.add(maxHint, c);
		r++;
		c.gridwidth = 1;
		c.gridx = 0;
		c.gridy = r;
		p.add(new JLabel("Parse timeout (sec):"), c);
		c.gridx = 1;
		p.add(timeout, c);
		r++;
		c.gridx = 0;
		c.gridy = r;
		c.gridwidth = 2;
		p.add(timeoutHint, c);
		r++;
		c.gridwidth = 1;
		c.gridx = 0;
		c.gridy = r;
		p.add(new JLabel("Preview timeout (sec):"), c);
		c.gridx = 1;
		p.add(previewTimeout, c);
		r++;
		c.gridx = 0;
		c.gridy = r;
		c.gridwidth = 2;
		p.add(previewTimeoutHint, c);
		r++;
		c.gridwidth = 1;
		c.gridx = 0;
		c.gridy = r;
		p.add(new JLabel("Max extract chars:"), c);
		c.gridx = 1;
		p.add(maxExtractChars, c);
		r++;
		c.gridx = 0;
		c.gridy = r;
		c.gridwidth = 2;
		p.add(maxExtractHint, c);
		r++;
		c.gridwidth = 1;
		c.gridx = 0;
		c.gridy = r;
		p.add(new JLabel("Include extensions (comma):"), c);
		c.gridx = 1;
		p.add(include, c);
		r++;
		c.gridx = 0;
		c.gridy = r;
		c.gridwidth = 2;
		p.add(includeHint, c);
		r++;
		c.gridwidth = 1;
		c.gridx = 0;
		c.gridy = r;
		p.add(new JLabel("Exclude globs (semicolon):"), c);
		c.gridx = 1;
		p.add(new JScrollPane(exclude), c);
		r++;
		c.gridx = 0;
		c.gridy = r;
		c.gridwidth = 2;
		p.add(excludeHint, c);
		c.gridwidth = 1;

		int ret = JOptionPane.showConfirmDialog(this, p, "Indexing Settings", JOptionPane.OK_CANCEL_OPTION,
				JOptionPane.PLAIN_MESSAGE);
		if (ret == JOptionPane.OK_OPTION) {
			s.maxFileMB = ((Number) maxMb.getValue()).longValue();
			s.parseTimeoutSec = ((Number) timeout.getValue()).intValue();
			s.previewTimeoutSec = ((Number) previewTimeout.getValue()).intValue();
			s.maxExtractChars = ((Number) maxExtractChars.getValue()).intValue();
			s.includeExt = new java.util.ArrayList<>(
					FilterState.parseExts(include.getText()));
			s.excludeGlob = java.util.Arrays.asList(exclude.getText().split(";"));
			cm.saveIndexSettings(s);
			cm.setIndexDir(java.nio.file.Paths.get(indexDirField.getText()));
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
			JOptionPane.showMessageDialog(this, "No sources configured. Use 'Manage Sources...' first.");
			return;
		}
		org.abitware.docfinder.index.ConfigManager cm = new org.abitware.docfinder.index.ConfigManager();
		org.abitware.docfinder.index.IndexSettings s = cm.loadIndexSettings();

		java.nio.file.Path indexDir = sm.getIndexDir();

		// Disable UI and set indexing flag. Pending service opens and queued
		// searches are invalidated first so the rebuild period is reported as
		// INDEX_UNAVAILABLE, never as "No results" (D8).
		isIndexing = true;
		setIndexingUiEnabled(false);
		openServiceSequence++;
		stopSearchDebounce();
		searchSequence++;
		if (activeSearchWorker != null) {
			activeSearchWorker.cancel(true);
			activeSearchWorker = null;
		}
		setSearchService(null);
        statusLabel.setText("Rebuilding index (full)… Searching is disabled.");
		setBusyProgress(true, "Rebuilding index...");
		long t0 = System.currentTimeMillis();

		new javax.swing.SwingWorker<Integer, Void>() {
			@Override
			protected Integer doInBackground() throws Exception {
				if (hasWriteLock(indexDir)) {
					log.warn("Detected index write lock at {}. Force unlock and reset index before rebuild.", indexDir);
					forceUnlockAndResetIndex(indexDir);
				}
				try (org.abitware.docfinder.index.LuceneIndexer idx = new org.abitware.docfinder.index.LuceneIndexer(
						indexDir, s)) {
					return idx.indexFolders(sources, true); // full = true
				}
			}

			@Override
			protected void done() {
				try {
					int n = get();
					long ms = System.currentTimeMillis() - t0;
					statusLabel.setText("Rebuilt files: " + n + " | Time: " + ms + " ms | Index: " + indexDir);
					// 后台打开并统一发布（D8）
					org.abitware.docfinder.index.IndexSettings svc_s = new org.abitware.docfinder.index.ConfigManager().loadIndexSettings();
					reopenSearchServiceAsync(indexDir, svc_s);
				} catch (Exception ex) {
					log.warn("Full rebuild failed: {}", String.valueOf(ex.getCause() != null ? ex.getCause() : ex));
					handleFullRebuildFailure();
				} finally {
					// Re-enable UI and clear indexing flag
					isIndexing = false;
					setIndexingUiEnabled(true);
					setBusyProgress(false, "");
					refreshSourceDirectoriesAsync();
				}
			}
		}.execute();
	}

	/**
	 * A failed full rebuild leaves the index unusable (old data was reset), so
	 * the service is not republished: searches keep reporting INDEX_UNAVAILABLE
	 * until a later successful rebuild/Index All reopens it.
	 */
	void handleFullRebuildFailure() {
		openServiceSequence++;
		// Invalidate/cancel in-flight searches directly so this method stays
		// correct if it is ever reused outside the rebuild worker path.
		stopSearchDebounce();
		searchSequence++;
		if (activeSearchWorker != null) {
			activeSearchWorker.cancel(true);
			activeSearchWorker = null;
		}
		setSearchService(null);
		clearPresentedResults("Rebuild failed.");
		statusLabel.setText("Rebuild failed. Search is unavailable until the index is rebuilt. See the application log.");
	}

	private boolean hasWriteLock(java.nio.file.Path indexDir) {
		java.nio.file.Path lockFile = indexDir.resolve(org.apache.lucene.index.IndexWriter.WRITE_LOCK_NAME);
		return java.nio.file.Files.exists(lockFile);
	}

	private void forceUnlockAndResetIndex(java.nio.file.Path indexDir) throws java.io.IOException {
		stopIndexServicesForLockRecovery();
		if (!java.nio.file.Files.exists(indexDir)) {
			java.nio.file.Files.createDirectories(indexDir);
			return;
		}
		java.nio.file.Files.walkFileTree(indexDir, new java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
			@Override
			public java.nio.file.FileVisitResult visitFile(java.nio.file.Path file,
					java.nio.file.attribute.BasicFileAttributes attrs) throws java.io.IOException {
				java.nio.file.Files.deleteIfExists(file);
				return java.nio.file.FileVisitResult.CONTINUE;
			}

			@Override
			public java.nio.file.FileVisitResult postVisitDirectory(java.nio.file.Path dir, java.io.IOException exc)
					throws java.io.IOException {
				if (exc != null) {
					throw exc;
				}
				if (!dir.equals(indexDir)) {
					java.nio.file.Files.deleteIfExists(dir);
				}
				return java.nio.file.FileVisitResult.CONTINUE;
			}
		});
	}

	private void stopIndexServicesForLockRecovery() {
		try {
			if (liveService != null) {
				liveService.close();
				liveService = null;
			}
		} catch (Exception ex) {
			log.warn("Failed to stop live watch before lock recovery: {}", ex.getMessage());
		}
		try {
			if (netPoller != null) {
				netPoller.close();
				netPoller = null;
			}
		} catch (Exception ex) {
			log.warn("Failed to stop network polling before lock recovery: {}", ex.getMessage());
		}
		try {
			openServiceSequence++; // invalidate any pending service open candidates
			// Route through setSearchService so the web server also observes the
			// service going away (INDEX_UNAVAILABLE), and so the close still
			// completes before index files are deleted below.
			if (javax.swing.SwingUtilities.isEventDispatchThread()) {
				setSearchService(null);
			} else {
				javax.swing.SwingUtilities.invokeAndWait(() -> setSearchService(null));
			}
		} catch (Exception ex) {
			log.warn("Failed to stop search service before lock recovery: {}", ex.getMessage());
		}

		javax.swing.SwingUtilities.invokeLater(() -> {
			if (liveWatchToggle != null) {
				liveWatchToggle.setSelected(false);
			}
			if (netPollToggle != null) {
				netPollToggle.setSelected(false);
			}
		});
	}

	private void onDirectoryScopeCommitted() {
		// A committed scope change re-runs the current query once (no-op when
		// the query box is empty; empty input clears results inside doSearch).
		doSearch();
	}

	/**
	 * Background refresh of the directory-scope source list. Latest
	 * generation wins; stale snapshots are dropped on the EDT (D5). Shows a
	 * one-shot warning when rows were skipped or the file cannot be read —
	 * the next clean refresh clears it.
	 */
	private void refreshSourceDirectoriesAsync() {
		final long gen = ++sourceLoadSequence;
		new Thread(() -> {
			java.util.List<String> dirs = new java.util.ArrayList<>();
			boolean warn = false;
			try {
				org.abitware.docfinder.index.SourceManager sm =
						new org.abitware.docfinder.index.SourceManager();
				org.abitware.docfinder.index.SourceManager.SearchRootsSnapshot snap =
						sm.loadSearchRootsSnapshot();
				for (java.nio.file.Path p : snap.roots) {
					dirs.add(p.toString());
				}
				warn = !snap.skippedLineNumbers.isEmpty();
			} catch (java.io.IOException e) {
				log.warn("Could not load search sources for scope panel: {}", e.getMessage());
				warn = true;
			}
			final boolean warnFinal = warn;
			SwingUtilities.invokeLater(() -> {
				if (gen != sourceLoadSequence) {
					return;
				}
				directoryScopePanel.setSourceDirectories(dirs);
				directoryScopePanel.setSourceWarning(warnFinal);
			});
		}, "docfinder-source-load").start();
	}

	/**
	 * Opens a Lucene search service off the EDT and publishes it through the
	 * single setSearchService path (D8). Stale candidates (window closed,
	 * rebuild started, superseded by a newer open) are closed, never
	 * published.
	 */
	public void reopenSearchServiceAsync(java.nio.file.Path indexDir,
			org.abitware.docfinder.index.IndexSettings settings) {
		reopenSearchServiceAsync(indexDir, settings, null);
	}

	/**
	 * completionStatus, when non-null, is the fixed status text shown after a
	 * successful publish — used after partial index failures so "Ready" does
	 * not overwrite the failure notice (D8).
	 */
	void reopenSearchServiceAsync(java.nio.file.Path indexDir,
			org.abitware.docfinder.index.IndexSettings settings, String completionStatus) {
		final long seq = ++openServiceSequence;
		new Thread(() -> {
			SearchService candidate = null;
			try {
				candidate = openSearchService(indexDir, settings);
			} catch (java.io.IOException e) {
				log.warn("Could not open search service at {}: {}", indexDir, e.getMessage());
			}
			final SearchService opened = candidate;
			SwingUtilities.invokeLater(() -> {
				if (seq != openServiceSequence || opened == null) {
					// Stale or failed candidate: release, never publish.
					if (opened != null) {
						opened.close();
					}
					if (opened == null && seq == openServiceSequence) {
						// A failed open means the index is unavailable — the old
						// service must not silently keep answering queries while
						// the UI reports unavailable (DS-12). Invalidate
						// in-flight queries first so a late success or late
						// error from the old index cannot repopulate the table
						// or overwrite the unavailable status (R24-01).
						searchSequence++;
						if (activeSearchWorker != null) {
							activeSearchWorker.cancel(true);
							activeSearchWorker = null;
						}
						setSearchService(null);
						clearPresentedResults("Search index is unavailable.");
						statusLabel.setText(org.abitware.docfinder.search.SearchErrorMessages
								.forCode(org.abitware.docfinder.search.SearchException.Code.INDEX_UNAVAILABLE));
					}
					return;
				}
				setSearchService(opened);
				if (completionStatus != null) {
					statusLabel.setText(completionStatus);
				}
				refreshSourceDirectoriesAsync();
			});
		}, "docfinder-service-open").start();
	}

	/**
	 * Factory hook for the harness: the ONLY place production code may
	 * construct LuceneSearchService (D8). Tests may override to return fakes
	 * or control timing.
	 */
	protected SearchService openSearchService(java.nio.file.Path indexDir,
			org.abitware.docfinder.index.IndexSettings settings) throws java.io.IOException {
		return new org.abitware.docfinder.search.LuceneSearchService(indexDir, settings);
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
		// Every explicit submission path (Enter, history pick, scope/mode/dir
		// change, Clear, quick action, Apply filters, the debounce fire itself)
		// cancels any pending debounce — class B submits exactly once (D6).
		stopSearchDebounce();
		long token = ++searchSequence;
		if (isIndexing) {
			clearPresentedResults("Indexing in progress.");
			statusLabel.setText("Indexing in progress, searching is disabled.");
			return;
		}

		if (searchService == null) {

			clearPresentedResults(org.abitware.docfinder.search.SearchErrorMessages

					.forCode(org.abitware.docfinder.search.SearchException.Code.INDEX_UNAVAILABLE));

			statusLabel.setText(org.abitware.docfinder.search.SearchErrorMessages

					.forCode(org.abitware.docfinder.search.SearchException.Code.INDEX_UNAVAILABLE));

			return;

		}



		FilterState filters = buildFilterState();

		String q = getQueryText();
		String directory = directoryScopePanel.getDirectoryPath();

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



		SearchWorker worker = new SearchWorker(token, q, filters, getSelectedScope(), getSelectedMatchMode(), directory);

		activeSearchWorker = worker;



		statusLabel.setText("Searching...");

		updatePreviewInner("Searching...", false);

		searchExecutor.submit(worker);

	}



	private void populateResults(String query, List<SearchResult> list, long elapsedMs,
			org.abitware.docfinder.search.TriState hasMore) {

		if (list == null) {

			list = Collections.emptyList();

		}



		currentResults = new java.util.ArrayList<>(list);
		model.setRowCount(0);

		for (SearchResult r : list) {

			model.addRow(new Object[] { r.name, r.path, r.score, Long.valueOf(r.sizeBytes),

				fmtTime(r.ctime), fmtTime(r.atime),

				(r.match == null ? "" : r.match) });

		}



		if (list.isEmpty()) {

			statusLabel.setText(String.format("No results. | %d ms", elapsedMs));

			resultTable.clearSelection();

			updatePreviewInner("No results.");

		} else {

			// Tri-state truncation hint (D5): TRUE claims more exist, FALSE
			// claims complete, UNKNOWN must not claim either.
			if (hasMore == org.abitware.docfinder.search.TriState.TRUE) {
				statusLabel.setText(String.format("Showing first %d results  |  %d ms", list.size(), elapsedMs));
			} else {
				statusLabel.setText(String.format("Results: %d  |  %d ms", list.size(), elapsedMs));
			}

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

		private final String directoryPath;

		private org.abitware.docfinder.search.SearchOutcome outcome;

		private final long startedAt = System.currentTimeMillis();



		SearchWorker(long token, String query, FilterState filter, SearchScope scope, MatchMode matchMode, String directoryPath) {
			this.token = token;
			this.query = query;
			this.filter = filter;
			this.scope = (scope == null) ? SearchScope.ALL : scope;
			this.matchMode = (matchMode == null) ? MatchMode.FUZZY : matchMode;
			this.directoryPath = directoryPath;
			log.debug("SearchWorker created for query: {} with token: {}", query, token);
		}



		@Override

		protected List<SearchResult> doInBackground() {
			log.debug("SearchWorker.doInBackground() started for token: {}", token);
			if (isCancelled()) {
				log.debug("SearchWorker.doInBackground() for token {} was cancelled before execution.", token);
				return Collections.emptyList();
			}
			// Read the volatile field exactly once into a local (D8); a null
			// here means the index went away after submission and must be
			// reported, not shown as "No results".
			SearchService svc = searchService;
			if (svc == null) {
				log.warn("SearchService is null in SearchWorker.doInBackground() for token: {}", token);
				throw new org.abitware.docfinder.search.SearchException(
						org.abitware.docfinder.search.SearchException.Code.INDEX_UNAVAILABLE,
						"search service became unavailable before the worker ran");

			}

			SearchRequest request = new SearchRequest(query, 100, filter, scope, matchMode, directoryPath);
			log.debug("Executing search for token: {}", token);
			outcome = svc.searchDetailed(request);
			log.debug("Search for token {} completed with {} results.", token, outcome.results.size());
			return outcome.results;

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
				// Map typed failures to fixed text; never leak raw messages
				// or stack traces into the status bar (D3).
				org.abitware.docfinder.search.SearchException.Code code =
						org.abitware.docfinder.search.SearchException.Code.SEARCH_FAILED;
				if (cause instanceof org.abitware.docfinder.search.SearchException) {
					code = ((org.abitware.docfinder.search.SearchException) cause).getCode();
				} else {
					log.warn("Search failed for token {}: {}", token, String.valueOf(cause));
				}
				clearResultsForError();
				statusLabel.setText(org.abitware.docfinder.search.SearchErrorMessages.forCode(code));

				return;

			} catch (Exception ex) {

				clearResultsForError();
				statusLabel.setText(org.abitware.docfinder.search.SearchErrorMessages
						.forCode(org.abitware.docfinder.search.SearchException.Code.SEARCH_FAILED));

				return;

			}



			if (token != searchSequence) {

				return;

			}



			long elapsed = Math.max(0L, System.currentTimeMillis() - startedAt);

			org.abitware.docfinder.search.TriState hasMore = (outcome == null)
					? org.abitware.docfinder.search.TriState.UNKNOWN : outcome.hasMore;
			populateResults(query, list, elapsed, hasMore);

		}

	}



	private static java.nio.file.Path parentOfSafe(String path) {
		try {
			return java.nio.file.Paths.get(path).getParent();
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * Commits a directory scope chosen from a result row and re-runs the
	 * current query exactly once. Programmatic set does not fire the panel
	 * callback — the single doSearch() below is the only submission (A5b).
	 */
	void searchInDirectory(String directoryPath) {
		if (directoryPath == null || directoryPath.trim().isEmpty()) {
			return;
		}
		directoryScopePanel.setDirectoryPath(directoryPath);
		doSearch();
	}

	private void clearResultsForError() {
		clearPresentedResults("Search failed.");
	}

	private void clearPresentedResults(String previewText) {
		model.setRowCount(0);
		resultTable.clearSelection();
		currentResults = java.util.Collections.emptyList();
		updatePreviewInner(previewText);
	}

	private void addToHistory(String q) {
		q = (q == null) ? "" : q.trim();
		if (q.isEmpty())
			return;

		List<String> latest = historyMgr.addAndSave(q);

        // 更新下拉模型：去重reset顶、最多100
		javax.swing.DefaultComboBoxModel<String> m = (javax.swing.DefaultComboBoxModel<String>) queryBox.getModel();
		// Preserve the user's current draft + caret across the model rebuild:
		// removeAllElements may reset the editor, and a stale-result rewrite
		// must never clobber text typed while the search was in flight (D6 C).
		javax.swing.JTextField tf = getActiveSearchField();
		String draft = (tf == null) ? "" : tf.getText();
		int caret = (tf == null) ? 0 : tf.getCaretPosition();
		suppressQueryActions = true;
        // 简单粗暴：清空重加（100 项以内性能无感）
		m.removeAllElements();
		for (String s : latest)
			m.addElement(s);
		suppressQueryActions = false;
		setQueryText(draft);
		if (tf != null) {
			tf.setCaretPosition(Math.min(caret, draft.length()));
		}
	}

    // 新增方法：
	private void installTablePopupActions() {
		rowPopup = new JPopupMenu();

		JMenuItem openItem = new JMenuItem("Open");
		JMenuItem revealItem = new JMenuItem("Reveal in Explorer");
		JMenuItem showProperty = new JMenuItem("Show Properties");
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
		// Directory-scope quick actions (A5b/DS-11): folder → itself,
		// file → its parent directory; keeps the current query/filters and
		// commits exactly once via the panel's programmatic setter + doSearch.
		JMenuItem searchInThisFolder = this.searchInThisFolderItem = new JMenuItem("Search in this folder");
		searchInThisFolder.setToolTipText("Limit search to this folder and all its subfolders");
		searchInThisFolder.addActionListener(e -> {
			SearchResult r = getSelectedResult();
			if (r == null || !r.directory) return;
			searchInDirectory(r.path);
		});
		JMenuItem searchInContaining = this.searchInContainingItem = new JMenuItem("Search in containing folder");
		searchInContaining.setToolTipText("Limit search to the folder containing this file");
		searchInContaining.addActionListener(e -> {
			SearchResult r = getSelectedResult();
			if (r == null || r.directory) return;
			try {
				java.nio.file.Path parent = java.nio.file.Paths.get(r.path).getParent();
				if (parent != null) {
					searchInDirectory(parent.toString());
				}
			} catch (Exception ex) {
				log.warn("Cannot resolve parent of {}: {}", r.path, ex.getMessage());
			}
		});

		rowPopup.add(openItem);
		rowPopup.add(openWith);
		rowPopup.add(revealItem);
		rowPopup.add(showProperty);
		rowPopup.addSeparator();
		rowPopup.add(searchInThisFolder);
		rowPopup.add(searchInContaining);
		rowPopup.addSeparator();
		rowPopup.add(copyMenu);

		// Copy Web Link (only enabled when web server is running)
		JMenuItem copyWebLink = new JMenuItem("Copy Web Link");
		copyWebLink.setToolTipText("Copy a shareable web preview link (requires web server to be running)");
		copyWebLink.addActionListener(e -> {
			RowSel s = getSelectedRow();
			if (s == null) return;
			if (webServer == null || !webServer.isRunning()) {
				JOptionPane.showMessageDialog(this,
						"Web server is not running.\nEnable it via File → Enable Web Server first.",
						"Copy Web Link", JOptionPane.INFORMATION_MESSAGE);
				return;
			}
            String link = null;
            try {
                link = webServer.getBaseUrl() + "/preview?path=" + java.net.URLEncoder.encode(s.path, java.nio.charset.StandardCharsets.UTF_8.name());
            } catch (UnsupportedEncodingException ex) {
                throw new RuntimeException(ex);
            }
            setClipboard(link);
			statusLabel.setText("Web link copied: " + link);
		});
		rowPopup.add(copyWebLink);
		// Dynamically enable/disable items when the popup opens
		rowPopup.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
			@Override public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent ev) {
				copyWebLink.setEnabled(webServer != null && webServer.isRunning());
				SearchResult r = getSelectedResult();
				boolean folderRow = r != null && r.directory;
				searchInThisFolder.setEnabled(folderRow && !isIndexing);
				searchInContaining.setEnabled(r != null && !folderRow && !isIndexing
						&& parentOfSafe(r.path) != null);
			}
			@Override public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent ev) {}
			@Override public void popupMenuCanceled(javax.swing.event.PopupMenuEvent ev) {}
		});

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
		showProperty.addActionListener(e -> showSearchPropertyDialog());

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
					if (c == 3 && val instanceof Number) {
						cells.add(csvQuote(fmtSize(((Number) val).longValue())));
					} else {
						cells.add(csvQuote(val == null ? "" : val.toString()));
					}
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
		final int modelRow;

		RowSel(String n, String p, int modelRow) {
			name = n;
			path = p;
			this.modelRow = modelRow;
		}
	}

	private void showSearchPropertyDialog() {
		SearchResult r = getSelectedResult();
		if (r == null) {
			JOptionPane.showMessageDialog(this, "No selected result.");
			return;
		}

		StringBuilder sb = new StringBuilder();
		sb.append("Name: ").append(r.name).append("\n");
		sb.append("Path: ").append(r.path).append("\n");
		sb.append("Type: ").append(r.directory ? "Folder" : "File").append("\n");
		sb.append("Size: ").append(fmtSize(r.sizeBytes)).append("\n");
		sb.append("Created: ").append(fmtTime(r.ctime)).append("\n");
		sb.append("Accessed: ").append(fmtTime(r.atime)).append("\n");
		sb.append("Match Type: ").append(r.match == null ? "" : r.match).append("\n");
		sb.append("Score: ").append(String.format("%.5f", r.score)).append("\n\n");

		sb.append("Query Context\n");
		sb.append("- Query: ").append((lastQuery == null || lastQuery.trim().isEmpty()) ? "<empty>" : lastQuery.trim()).append("\n");
		sb.append("- Scope: ").append(getSelectedScope()).append("\n");
		sb.append("- Match mode: ").append(getSelectedMatchMode()).append("\n");
		FilterState fs = buildFilterState();
		sb.append("- Filter ext: ").append(fs.exts == null ? "[]" : fs.exts).append("\n");
		sb.append("- Filter from: ").append(fs.fromEpochMs == null ? "" : fs.fromEpochMs).append("\n");
		sb.append("- Filter to: ").append(fs.toEpochMs == null ? "" : fs.toEpochMs).append("\n\n");

		sb.append("Scoring Note\n");
		sb.append("- Score is Lucene relevance score (BM25-based by default).\n");
		sb.append("- It is influenced by term frequency, inverse document frequency, field boosts,\n");
		sb.append("  and query structure (e.g., name/content scope and filters).\n");
		sb.append("- Match Type is derived by checking whether name/content conditions matched this document.\n");

		JTextArea area = new JTextArea(sb.toString());
		area.setEditable(false);
		area.setCaretPosition(0);
		area.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12));
		JScrollPane sp = new JScrollPane(area);
		sp.setPreferredSize(new java.awt.Dimension(780, 520));
		JOptionPane.showMessageDialog(this, sp, "Search Result Properties", JOptionPane.INFORMATION_MESSAGE);
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

                "<h3>Quick Start</h3>" + "<ol>" + "<li>Open <b>File → Manage Sources…</b> to add folders.</li>"
                + "<li>Run <b>File → Index All Sources</b> to build/update the index, or <b>Rebuild Index (Full)</b> to recreate it from scratch.</li>"
                + "<li>Type your query and press <b>Enter</b>.</li>" + "</ol>" +

                "<h3>Directory Scope</h3>" + "<ul>"
                + "<li>Select a configured source under <b>Search in</b>, or choose <b>Custom</b> and enter an absolute directory.</li>"
                + "<li>The scope includes all subfolders and searches indexed content only. FOLDER mode can also match the selected directory itself.</li>"
                + "<li>Path edits are a draft until Enter, source selection or Browse confirmation. Check <b>Applied</b> for the submitted scope; Clear returns to all sources.</li>"
                + "<li>Right-click a result: <b>Search in this folder</b> or <b>Search in containing folder</b>.</li>"
                + "<li>For old indexes, try <b>Index All Sources</b> first. Check offline sources and back up before a manual Full Rebuild if legacy records remain. Rebuild is non-atomic and search is unavailable during it.</li>"
                + "<li>Default (Fuzzy) search also matches filename middle fragments: <code>report</code> finds <code>AnnualReport2026.pdf</code>. Multiple words must all match the name. Name matches rank above content-only hits.</li>"
                + "<li>Typing searches automatically after a short pause; Enter or a scope change searches immediately.</li>"
                + "<li>EXACT includes analyzed phrase matching for the selected fields; it is not restricted to full filename equality.</li>"
                + "</ul>" +

                "<h3>Indexing Tips</h3>" + "<ul>"
                + "<li>Use <b>File → Indexing Settings…</b> to adjust max size, timeout, and include/exclude patterns.</li>"
                + "<li><b>Poll Network Sources Now</b> runs a one-time network sync without blocking the UI.</li>"
                + "</ul>" +

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
                + "<li>Previews auto-detect common encodings (UTF-8/UTF-16, Shift-JIS, GBK, Big5, EUC-KR) when possible.</li>"
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
        new org.abitware.docfinder.ui.LogViewer(this).setVisible(true);
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

    @Override
    public void onToggleWebServer() {
        toggleWebServer();
    }

    @Override
    public void onOpenWebInterface() {
        openWebInterface();
    }

    @Override
    public void onToggleScoreColumn(boolean visible) {
        setScoreColumnVisible(visible);
        new org.abitware.docfinder.index.ConfigManager().setShowScoreColumn(visible);
    }

    /** Shows or hides the Score column at index SCORE_COL by setting its min/max/preferred width. */
    private void setScoreColumnVisible(boolean visible) {
        javax.swing.table.TableColumn col = resultTable.getColumnModel().getColumn(SCORE_COL);
        if (visible) {
            col.setMinWidth(15);
            col.setMaxWidth(Integer.MAX_VALUE);
            col.setPreferredWidth(70);
        } else {
            col.setMinWidth(0);
            col.setMaxWidth(0);
            col.setPreferredWidth(0);
        }
    }

    /**
     * Sets the WebServer instance so the menu can start/stop it.
     * Call this from App after the web server is (conditionally) created.
     */
    public void setWebServer(org.abitware.docfinder.web.WebServer ws) {
        this.webServer = ws;
        SwingUtilities.invokeLater(() -> {
            boolean running = (ws != null && ws.isRunning());
            if (webServerToggle != null) webServerToggle.setSelected(running);
            if (openWebItem != null) openWebItem.setEnabled(running);
        });
    }

    private void toggleWebServer() {
        final boolean enable = webServerToggle != null && webServerToggle.isSelected();
        if (webServerToggle != null) webServerToggle.setEnabled(false);
        statusLabel.setText(enable ? "Starting web server…" : "Stopping web server…");

        new javax.swing.SwingWorker<Void, Void>() {
            private String message;
            private boolean success = false;
            private String url;

            @Override
            protected Void doInBackground() {
                try {
                    org.abitware.docfinder.index.ConfigManager cm = new org.abitware.docfinder.index.ConfigManager();
                    if (enable) {
                        if (webServer == null) {
                            webServer = new org.abitware.docfinder.web.WebServer(
                                    cm.getWebPort(), cm.getWebBindAddress());
                            // pass current search service if already available
                            if (searchService != null) webServer.setSearchService(searchService);
                        }
                        webServer.start();
                        cm.setWebEnabled(true);
                        url = webServer.getBaseUrl() + "/";
                    } else {
                        if (webServer != null) webServer.stop();
                        cm.setWebEnabled(false);
                    }
                    success = true;
                } catch (Exception ex) {
                    message = (enable ? "Failed to start web server:\n" : "Failed to stop web server:\n")
                            + ex.getMessage();
                }
                return null;
            }

            @Override
            protected void done() {
                if (webServerToggle != null) webServerToggle.setEnabled(true);
                if (!success) {
                    if (webServerToggle != null) webServerToggle.setSelected(!enable);
                    if (message != null) {
                        JOptionPane.showMessageDialog(MainWindow.this, message,
                                enable ? "Web Server" : "Web Server", JOptionPane.ERROR_MESSAGE);
                    }
                } else {
                    if (openWebItem != null) openWebItem.setEnabled(enable);
                    if (enable) {
                        statusLabel.setText("Web server started: " + url);
                    } else {
                        statusLabel.setText("Web server stopped.");
                    }
                }
            }
        }.execute();
    }

    private void openWebInterface() {
        if (webServer == null || !webServer.isRunning()) {
            JOptionPane.showMessageDialog(this, "Web server is not running.",
                    "Open Web Interface", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String url = webServer.getBaseUrl() + "/";
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new java.net.URI(url));
            } else {
                // Fallback: show URL so user can copy-paste
                JOptionPane.showMessageDialog(this,
                        "Open the following URL in your browser:\n" + url,
                        "Open Web Interface", JOptionPane.INFORMATION_MESSAGE);
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this,
                    "Could not open browser:\n" + ex.getMessage(),
                    "Open Web Interface", JOptionPane.ERROR_MESSAGE);
        }
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

	/** Current search service, for tests/harness only. */
	protected SearchService getCurrentSearchService() {
		return searchService;
	}

	/** Directory-scope panel, for tests/harness only. */
	protected org.abitware.docfinder.ui.components.DirectoryScopePanel getDirectoryScopePanel() {
		return directoryScopePanel;
	}

	public void setSearchService(SearchService newSvc) {
		if (this.searchService != null && this.searchService != newSvc) {
			this.searchService.close(); // Close the old service (same instance is kept)
		}
		this.searchService = newSvc;
		// Keep the web server in sync with the latest search service
		if (webServer != null) webServer.setSearchService(newSvc);
		if (newSvc == null) {
			statusLabel.setText("Search index is initializing...");
		} else {
			statusLabel.setText("Ready");
		}
	}

	/** Called by background watcher/poller after commit; notifies search service to refresh NRT reader */
	private void onIndexCommit() {
		SearchService svc = searchService;
		if (svc != null) {
			svc.notifyIndexCommit();
		}
	}
	@Override
	public void dispose() {
		stopSearchDebounce();
		try {
			UIManager.removePropertyChangeListener(lafListener);
		} catch (Exception ignore) {
		}
		openServiceSequence++; // invalidate pending service opens; late candidates get closed
		sourceLoadSequence++;
		if (searchService != null) { // Close search service on dispose
			searchService.close();
		}
		searchExecutor.shutdownNow();
		super.dispose();
	}

}
