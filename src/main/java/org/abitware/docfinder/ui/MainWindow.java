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
import java.util.List;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;

import org.abitware.docfinder.search.FilterState;
import org.abitware.docfinder.search.MatchMode;
import org.abitware.docfinder.search.SearchRequest;
import org.abitware.docfinder.search.SearchScope;
import org.abitware.docfinder.search.SearchResult;
import org.abitware.docfinder.search.SearchService;
import org.abitware.docfinder.watch.NetPollerService.PollStats;

public class MainWindow extends JFrame {
	private org.abitware.docfinder.watch.LiveIndexService liveService;
	private javax.swing.JCheckBoxMenuItem liveWatchToggle;

	private javax.swing.JCheckBoxMenuItem netPollToggle;
	private org.abitware.docfinder.watch.NetPollerService netPoller;

	// ========= 瀛楁 =========
	private SearchService searchService;

	// 椤堕儴锛氭悳绱笌杩囨护
	// 鎼滅储妗嗘敼涓衡€滃彲缂栬緫涓嬫媺鈥濓紝缂栬緫鍣ㄤ粛鏄?JTextField
	private final javax.swing.JComboBox<SearchScope> scopeBox = new javax.swing.JComboBox<>(SearchScope.values());
	private final javax.swing.JComboBox<MatchMode> matchModeBox = new javax.swing.JComboBox<>(MatchMode.values());
	private final javax.swing.JComboBox<String> queryBox = new javax.swing.JComboBox<>();
	private javax.swing.JTextField searchField; // 瀹為檯鐨勭紪杈戝櫒
	private final org.abitware.docfinder.search.SearchHistoryManager historyMgr = new org.abitware.docfinder.search.SearchHistoryManager();

	// Popup & 鈥淥pen With鈥?璁板繂椤癸紙渚涘彸閿彍鍗曞拰鍒锋柊浣跨敤锛?
	private JPopupMenu rowPopup;
	private JMenuItem rememberedOpenWithItem;

	private final JTextField extField = new JTextField(); // 閫楀彿鍒嗛殧鎵╁睍鍚?
	private JFormattedTextField fromField; // yyyy-MM-dd
	private JFormattedTextField toField; // yyyy-MM-dd
	private final JPanel filterBar = new JPanel(new BorderLayout(6, 6)); // 鍙姌鍙犺繃婊ゆ潯

	// 涓儴锛氱粨鏋?+ 棰勮
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

	// 搴曢儴锛氱姸鎬佹爮
	private final JLabel statusLabel = new JLabel("Ready");

	// 棰勮/鎼滅储涓婁笅鏂?
	private String lastQuery = "";

	private SearchWorker activeSearchWorker;
	private long searchSequence = 0L;

	// ========= 鏋勯€?=========
	public MainWindow(SearchService searchService) {
		super("DocFinder");
		this.searchService = searchService;

		setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
		setMinimumSize(new Dimension(900, 560));
		setLocationRelativeTo(null);
		getContentPane().setLayout(new BorderLayout());

		// 1) 椤堕儴 North锛氭悳绱㈡潯 + 鍙姌鍙犺繃婊ゆ潯
		JPanel north = new JPanel(new BorderLayout());
		north.add(buildTopBar(), BorderLayout.NORTH);
		north.add(buildFilterBar(), BorderLayout.CENTER); // 榛樿闅愯棌
		getContentPane().add(north, BorderLayout.NORTH);

		// 2) 涓儴 Center锛氱粨鏋滆〃 + 鍙充晶棰勮
		getContentPane().add(buildCenterAndPreview(), BorderLayout.CENTER);

		// 3) 搴曢儴 South锛氱姸鎬佹爮
		getContentPane().add(buildStatusBar(), BorderLayout.SOUTH);

		// 4) 鑿滃崟鏍忥紙File / Help锛?
		setJMenuBar(buildMenuBar());

		// 5) 鍙抽敭鑿滃崟銆佸揩鎹烽敭銆佽閫夋嫨浜嬩欢
		installTablePopupActions(); // 鍙抽敭锛歄pen / Reveal / Copy
		installTableShortcuts(); // Enter / Ctrl+C / Ctrl+Shift+C
		resultTable.getSelectionModel().addListSelectionListener(e -> {
			if (!e.getValueIsAdjusting())
				loadPreviewAsync();
		});

		setIconImages(org.abitware.docfinder.ui.IconUtil.loadAppImages());

		// 杩涗竴姝ワ細璁剧疆 Taskbar/Dock 鍥炬爣锛堟寫鏈€澶х殑閭ｅ紶锛?
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

	/** 椤堕儴鎼滅储鏉★紙鍚?Filters 鎸夐挳锛?*/
	private JComponent buildTopBar() {
		JPanel top = new JPanel(new BorderLayout(8, 8));
		top.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));

		scopeBox.setToolTipText("Search scope");
		scopeBox.setMaximumRowCount(SearchScope.values().length);
		scopeBox.setSelectedItem(SearchScope.ALL);

		matchModeBox.setToolTipText("Match mode");
		matchModeBox.setMaximumRowCount(MatchMode.values().length);
		matchModeBox.setSelectedItem(MatchMode.FUZZY);

		// 鍙紪杈戜笅鎷?
		queryBox.setEditable(true);
		queryBox.setToolTipText("Tips: name:<term>, content:<term>, phrase with quotes, AND/OR, wildcard *");

		// 鍙栧埌 editor 鐨?JTextField 浠ヤ究璁剧疆 placeholder 鍜岀洃鍚洖璋?
		searchField = (javax.swing.JTextField) queryBox.getEditor().getEditorComponent();
		searchField.putClientProperty("JTextField.placeholderText",
				"Search... (e.g. report*, content:\"zero knowledge\", name:\"瑷▓\")");
		// 鍥炶溅瑙﹀彂鎼滅储
		searchField.addActionListener(e -> doSearch());

		// 涓嬫媺閫夋嫨鏌愭潯鍘嗗彶鏃朵篃瑙﹀彂鎼滅储
		queryBox.addActionListener(e -> {
			Object sel = queryBox.getSelectedItem();
			if (sel != null && queryBox.isPopupVisible()) {
				setQueryText(sel.toString());
				doSearch();
			}
		});

		// 鍒濇鍔犺浇鍘嗗彶
		List<String> hist = historyMgr.load();
		for (String s : hist)
			queryBox.addItem(s);

		// 鍏抽敭锛氫繚鎸佺紪杈戝櫒涓虹┖锛宲laceholder 鎵嶄細鏄剧ず
		queryBox.setSelectedItem(""); // <-- 鏂板
		searchField.requestFocusInWindow(); // 鍙€夛細鎶婅緭鍏ョ劍鐐规斁鍒扮紪杈戝櫒

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

		top.add(new JLabel("馃攷"), BorderLayout.WEST);
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

	/** 杩囨护鏉★紙鎵╁睍鍚?+ 鏃堕棿鑼冨洿锛夛紝榛樿闅愯棌 */
	private JComponent buildFilterBar() {
		// 鏃ユ湡鏍煎紡鍣?
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
		filterBar.setVisible(false); // 榛樿鎶樺彔
		return filterBar;
	}

	/** 涓績鍖哄煙锛氱粨鏋滆〃 + 棰勮闈㈡澘锛堝垎鏍忥級 */
	private JComponent buildCenterAndPreview() {
		// 缁撴灉琛ㄥ熀纭€璁剧疆涓庡垪瀹?
		resultTable.setFillsViewportHeight(true);
		resultTable.setRowHeight(22);
		resultTable.setAutoCreateRowSorter(true);

		resultTable.getColumnModel().getColumn(0).setPreferredWidth(240); // Name
		resultTable.getColumnModel().getColumn(1).setPreferredWidth(480); // Path
		resultTable.getColumnModel().getColumn(2).setPreferredWidth(90); // Size 鉁?
		resultTable.getColumnModel().getColumn(3).setPreferredWidth(70); // Score
		resultTable.getColumnModel().getColumn(4).setPreferredWidth(130); // Created
		resultTable.getColumnModel().getColumn(5).setPreferredWidth(130); // Accessed
		resultTable.getColumnModel().getColumn(6).setPreferredWidth(110); // Match

		JScrollPane center = new JScrollPane(resultTable);

		// 棰勮锛氬彧璇?HTML锛屽瓧浣?11px锛堝皬涓€鐐癸級
		preview.setEditable(false);
		JScrollPane right = new JScrollPane(preview);
		right.setPreferredSize(new Dimension(360, 560));

		split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, center, right);
		split.setResizeWeight(0.72); // 宸︿晶涓诲垪琛ㄥ崰姣斿垎閰?
		return split;
	}

	/** 搴曢儴鐘舵€佹爮 */
	private JComponent buildStatusBar() {
		JPanel bottom = new JPanel(new BorderLayout());
		bottom.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
		bottom.add(statusLabel, BorderLayout.WEST);
		return bottom;
	}

	/** 鑿滃崟鏍忥紙File / Help锛?*/
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
		// Java 8 鐨勫揩鎹烽敭鍐欐硶锛歁enuShortcutKeyMask() + SHIFT_MASK
		clearHist.setAccelerator(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_DELETE,
				java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMask() | java.awt.event.InputEvent.SHIFT_MASK));
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

		file.addSeparator();

		JMenuItem exitItem = new JMenuItem("Exit");
		// Java 8锛氫娇鐢?getMenuShortcutKeyMask()锛圵in=Ctrl, macOS=Cmd锛?
		exitItem.setAccelerator(
				KeyStroke.getKeyStroke(KeyEvent.VK_Q, Toolkit.getDefaultToolkit().getMenuShortcutKeyMask()));

		exitItem.addActionListener(e -> {
			// 鍙€夛細浼橀泤鍋滄帀鏈湴鏂囦欢鐩戞帶 & 缃戠粶杞锛堝鏋滃綋鍓嶅紑鍚級
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

			// 鍙€夛細绉婚櫎鎵樼洏鍥炬爣
			try {
				if (SystemTray.isSupported()) {
					SystemTray tray = SystemTray.getSystemTray();
					for (TrayIcon ti : tray.getTrayIcons())
						tray.remove(ti);
				}
			} catch (Exception ignore) {
			}

			// 鍏抽棴绐楀彛骞堕€€鍑猴紙App 閲屾湁 shutdown hook 浼氭敞閿€鍏ㄥ眬鐑敭锛?
			try {
				dispose();
			} catch (Exception ignore) {
			}
			System.exit(0);
		});

		// add file menus
		file.add(exitItem);

		bar.add(file);

		// add theme
		bar.add(org.abitware.docfinder.ui.ThemeUtil.buildThemeMenu());

		// add help
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

		boolean ephemeral = (netPoller == null);
		if (ephemeral) {
			netPoller = new org.abitware.docfinder.watch.NetPollerService(sm.getIndexDir(), s, netRoots);
		}

		setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.WAIT_CURSOR));
		statusLabel.setText("Polling network sources鈥?");

		new javax.swing.SwingWorker<org.abitware.docfinder.watch.NetPollerService.PollStats, Void>() {
			@Override
			protected org.abitware.docfinder.watch.NetPollerService.PollStats doInBackground() throws Exception {
				return netPoller.pollNowAsync().get();
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
					if (ephemeral) {
						netPoller.close();
						netPoller = null;
					}
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

	/** 娓呯┖鎼滅储鍘嗗彶锛氱‘璁?-> 娓呯┖鎸佷箙鍖栨枃浠跺唴瀹?-> 娓呯┖涓嬫媺鍒楄〃 -> 娓呯┖杈撳叆妗?*/
	private void clearSearchHistory() {
		int ret = javax.swing.JOptionPane.showConfirmDialog(this,
				"This will remove all saved search queries.\nProceed?", "Clear Search History",
				javax.swing.JOptionPane.OK_CANCEL_OPTION, javax.swing.JOptionPane.WARNING_MESSAGE);
		if (ret != javax.swing.JOptionPane.OK_OPTION)
			return;

		try {
			// 1) 娓呯┖鎸佷箙鍖栧巻鍙?
			historyMgr.save(Collections.emptyList());

			// 2) 娓呯┖涓嬫媺妯″瀷
			javax.swing.DefaultComboBoxModel<String> m = (javax.swing.DefaultComboBoxModel<String>) queryBox.getModel();
			m.removeAllElements();

			// 3) 娓呯┖褰撳墠杈撳叆
			setQueryText("");

			// 4) 鐘舵€佹彁绀?
			statusLabel.setText("Search history cleared.");
			// 棰勮鍖虹粰涓交閲忔彁绀?
			updatePreviewInner("Search history cleared.");
		} catch (Exception ex) {
			javax.swing.JOptionPane.showMessageDialog(this, "Failed to clear history:\n" + ex.getMessage(), "Error",
					javax.swing.JOptionPane.ERROR_MESSAGE);
		}
	}

	/** 缁撴灉琛ㄥ揩鎹烽敭锛欵nter 鎵撳紑銆丆trl+C 澶嶅埗璺緞銆丆trl+Shift+C 澶嶅埗鍚嶇О */
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
		// 1) 鎵撳紑瀵硅瘽妗嗭紙modal锛岀敤鎴风紪杈?淇濆瓨婧愬垪琛級
		new org.abitware.docfinder.ui.ManageSourcesDialog(this).setVisible(true);

		// 2) 璇㈤棶鏄惁閲嶅惎 Live Watch / Poller锛堥伩鍏嶅繀鍗★級
		boolean needRestart = (liveWatchToggle != null && liveWatchToggle.isSelected())
				|| (netPollToggle != null && netPollToggle.isSelected());
		if (!needRestart)
			return;

		int ans = javax.swing.JOptionPane.showConfirmDialog(this, "Sources updated. Restart watchers/polling now?",
				"Apply Changes", javax.swing.JOptionPane.OK_CANCEL_OPTION, javax.swing.JOptionPane.QUESTION_MESSAGE);
		if (ans != javax.swing.JOptionPane.OK_OPTION)
			return;

		// 3) 鍚庡彴閲嶅惎锛堥伩鍏嶉樆濉?UI锛?
		setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.WAIT_CURSOR));
		statusLabel.setText("Applying source changes鈥?");

		new javax.swing.SwingWorker<Void, Void>() {
			@Override
			protected Void doInBackground() {
				try {
					if (liveWatchToggle != null && liveWatchToggle.isSelected()) {
						// 鍋?鍚?
						javax.swing.SwingUtilities.invokeLater(() -> {
							liveWatchToggle.setSelected(false);
							toggleLiveWatch();
						});
						// 绛夊緟 toggle 瀹屾瘯锛堢畝鍗?sleep锛岄伩鍏嶅湪 EDT 涓樆濉烇級
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
		statusLabel.setText("Indexing all sources鈥?");

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

	// 鐢?Tika 鍙鎶藉彇鐨勮交閲忛瑙堬紙鍦?EDT 涔嬪璺戯級
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


	// 鍙鎶藉彇鍓?N 瀛楃锛堝鐢ㄦ垜浠凡鏈夌殑 Tika 閫昏緫锛岀畝鍖栦负灞€閮ㄦ柟娉曚互鍏嶅惊鐜緷璧栵級
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

	// 浠庢煡璇覆閲屾彁鍙栬楂樹寒鐨勮瘝锛堥潪甯哥畝鍖栵細鍘绘帀瀛楁鍓嶇紑/寮曞彿/AND/OR锛?
	private String[] tokenizeForHighlight(String q) {
		if (q == null)
			return new String[0];
		q = q.replaceAll("(?i)\\b(name|content|path):", " "); // 鍘诲瓧娈靛墠缂€
		q = q.replace("\"", " ").replace("'", " ");
		q = q.replaceAll("(?i)\\bAND\\b|\\bOR\\b|\\bNOT\\b", " ");
		q = q.trim();
		if (q.isEmpty())
			return new String[0];
		// 鍒嗚瘝锛氭寜绌虹櫧鍒囷紱涓棩鏂囨儏鍐典笅鐩存帴淇濈暀鏁存璇?
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

	// 鐢熸垚鍖呭惈绗竴涓懡涓殑绠€鐭墖娈碉紙涓婁笅鏂?window锛?
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

	// 灏嗙墖娈佃浆鎴愮畝鍗?HTML 骞堕珮浜?<mark>
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
		sb.append("<html><body style='font-family:sans-serif;font-size:11px;line-height:1.4;padding:8px;");
		sb.append("color:").append(textColor).append(';');
		if (bgColor != null) {
			sb.append("background-color:").append(bgColor).append(';');
		}
		sb.append("'>");
		sb.append("<style>body a{color:").append(linkColor).append(";}</style>");
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

		// 绱㈠紩鐩綍锛氱敤鎴蜂富鐩綍涓?.docfinder/index
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
					// 鍒囨崲鍒?Lucene 鎼滅储鏈嶅姟
					setSearchService(new org.abitware.docfinder.search.LuceneSearchService(indexDir));
					// 鍙€夛細鑷姩瑙﹀彂涓€娆℃悳绱互楠岃瘉
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

	/** 寮哄埗鍏ㄩ噺閲嶅缓绱㈠紩锛圕REATE 妯″紡锛?*/
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
		statusLabel.setText("Rebuilding index (full)鈥?");
		long t0 = System.currentTimeMillis();

		new javax.swing.SwingWorker<Integer, Void>() {
			@Override
			protected Integer doInBackground() throws Exception {
				org.abitware.docfinder.index.LuceneIndexer idx = new org.abitware.docfinder.index.LuceneIndexer(
						indexDir, s);
				return idx.indexFolders(sources, true); // 鉁?full = true
			}

			@Override
			protected void done() {
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

	private SearchScope getSelectedScope() {
		Object sel = scopeBox.getSelectedItem();
		return (sel instanceof SearchScope) ? (SearchScope) sel : SearchScope.ALL;
	}

	private MatchMode getSelectedMatchMode() {
		Object sel = matchModeBox.getSelectedItem();
		return (sel instanceof MatchMode) ? (MatchMode) sel : MatchMode.FUZZY;
	}

	private void doSearch() {

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

		worker.execute();

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

		}



		@Override

		protected List<SearchResult> doInBackground() {

			if (isCancelled() || searchService == null) {

				return Collections.emptyList();

			}

			SearchRequest request = new SearchRequest(query, 100, filter, scope, matchMode);

			return searchService.search(request);

		}



		@Override

		protected void done() {

			if (token != searchSequence) {

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

		// 鏇存柊涓嬫媺妯″瀷锛氬幓閲嶇疆椤躲€佹渶澶?00
		javax.swing.DefaultComboBoxModel<String> m = (javax.swing.DefaultComboBoxModel<String>) queryBox.getModel();
		// 绠€鍗曠矖鏆达細娓呯┖閲嶅姞锛?00 椤逛互鍐呮€ц兘鏃犳劅锛?
		m.removeAllElements();
		for (String s : latest)
			m.addElement(s);
		queryBox.setSelectedItem(q); // 缃《鏄剧ず
	}

	// 鏂板鏂规硶锛?
	private void installTablePopupActions() {
		rowPopup = new JPopupMenu();

		JMenuItem openItem = new JMenuItem("Open");
		JMenuItem revealItem = new JMenuItem("Reveal in Explorer");
		JMenu copyMenu = new JMenu("Copy");
		JMenuItem copyName = new JMenuItem("Name");
		JMenuItem copyPath = new JMenuItem("Full Path");
		copyMenu.add(copyName);
		copyMenu.add(copyPath);

		// --- Open With鈥?瀛愯彍鍗?---
		JMenu openWith = new JMenu("Open With鈥?");
		JMenuItem chooseProg = new JMenuItem("Choose Program鈥?");
		openWith.add(chooseProg);
		openWith.addSeparator();
		rememberedOpenWithItem = new JMenuItem("(remembered)"); // 鐢ㄧ被瀛楁淇濆瓨
		rememberedOpenWithItem.setVisible(false);
		openWith.add(rememberedOpenWithItem);

		// 缁勮鑿滃崟
		rowPopup.add(openItem);
		rowPopup.add(openWith);
		rowPopup.add(revealItem);
		rowPopup.addSeparator();
		rowPopup.add(copyMenu);

		// 閫夋嫨绋嬪簭骞惰浣?
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

		// 鍏朵綑鍔ㄤ綔锛圤pen/Reveal/Copy锛変繚鎸佷綘涔嬪墠鐨勫疄鐜?..
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

		// 鍙抽敭瑙﹀彂锛氭寜涓?寮硅捣閮藉垽鏂?
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

	/** 鍙抽敭鑿滃崟寮瑰嚭锛屽苟鍔ㄦ€佸埛鏂扳€滆蹇嗙殑绋嬪簭鈥濋」 */
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
				// 閲嶆柊缁戝畾鍔ㄤ綔锛堝厛娓呮棫 listener锛?
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
				// 鈶?寮瑰嚭鑿滃崟鍓嶅姩鎬佸埛鏂?
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

	/** 瀵煎嚭褰撳墠琛ㄦ牸鍒?CSV锛圲TF-8, 鍚〃澶? 閫楀彿鍒嗛殧, 鑷姩鍔犲紩鍙凤級 */
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

			// 琛ㄥご
			int cols = model.getColumnCount();
			List<String> header = new java.util.ArrayList<>();
			for (int c = 0; c < cols; c++)
				header.add(csvQuote(model.getColumnName(c)));
			pw.write(String.join(",", header) + sep);

			// 鏁版嵁锛堟寜褰撳墠鎺掑簭鍚庣殑瑙嗗浘琛屽鍑猴級
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

	/** 鐢ㄦ寚瀹氱▼搴忔墦寮€鏂囦欢锛堣法骞冲彴澶勭悊锛?*/
	private void openWithProgram(String programAbsPath, String fileAbsPath) {
		try {
			if (isMac()) {
				// macOS: open -a <App> <file> (褰?programAbsPath 鏄?.app 鎴栧叾鍐呴儴浜岃繘鍒?
				new ProcessBuilder("open", "-a", programAbsPath, fileAbsPath).start();
			} else {
				// Windows / Linux: 鐩存帴鎵ц 绋嬪簭 + 鏂囦欢
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

	/** 璺ㄥ钩鍙扳€滃湪璧勬簮绠＄悊鍣ㄤ腑鏄剧ず鈥?*/
	private void revealInExplorer(String path) throws Exception {
		if (isWindows()) {
			new ProcessBuilder("explorer.exe", "/select,", path).start();
		} else if (isMac()) {
			new ProcessBuilder("open", "-R", path).start();
		} else {
			// Linux锛氶€€鑰屾眰鍏舵锛屾墦寮€鎵€鍦ㄧ洰褰?
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

				"<h3>Quick Start</h3>" + "<ol>" + "<li>Open <b>File 鈫?Index Sources鈥?/b> to add folders.</li>"
				+ "<li>Run <b>File 鈫?Index All Sources</b> to build/update the index, or <b>Rebuild Index (Full)</b> to recreate it from scratch.</li>"
				+ "<li>Type your query and press <b>Enter</b>.</li>" + "</ol>" +

				"<h3>Query Examples</h3>" + "<ul>" + "<li><code>report*</code> 鈥?prefix match on file name</li>"
				+ "<li><code>\"project plan\"</code> 鈥?phrase match</li>"
				+ "<li><code>content:kubernetes AND ingress</code> 鈥?content-only query</li>"
				+ "<li><code>name:\"瑷▓\"</code> / <code>content:\"瑷▓ 浠曟\"</code> 鈥?Japanese examples</li>" + "</ul>" +

				"<h3>Filters</h3>" + "<ul>" + "<li>Click <b>Filters</b> to toggle filter bar.</li>"
				+ "<li><b>Ext(s)</b>: comma-separated, e.g. <code>pdf,docx,txt</code>.</li>"
				+ "<li><b>From / To</b>: date range (yyyy-MM-dd) for modified time.</li>" + "</ul>" +

				"<h3>Shortcuts & Actions</h3>" + "<ul>" + "<li><b>Ctrl+Alt+Space</b> 鈥?toggle main window</li>"
				+ "<li><b>Enter</b> 鈥?run search / open selected file in results</li>"
				+ "<li><b>Ctrl+C</b> 鈥?copy full path; <b>Ctrl+Shift+C</b> 鈥?copy file name</li>"
				+ "<li><b>Alt+鈫?/b> 鈥?open query history dropdown</li>"
				+ "<li><b>Ctrl+Shift+Delete</b> 鈥?Clear Search History鈥?/li>"
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
		// 绠€鏄撲汉绫诲彲璇伙細B / KB / MB / GB
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

	public void setSearchService(SearchService svc) {
		this.searchService = svc;
	}
	@Override
	public void dispose() {
		try {
			UIManager.removePropertyChangeListener(lafListener);
		} catch (Exception ignore) {
		}
		super.dispose();
	}

}


