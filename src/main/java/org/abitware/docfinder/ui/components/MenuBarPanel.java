package org.abitware.docfinder.ui.components;

import java.awt.Desktop;
import java.awt.FlowLayout;
import java.awt.Toolkit;
import java.awt.event.KeyEvent;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import javax.swing.*;

/**
 * 菜单栏组件，包含所有菜单项和动作
 */
public class MenuBarPanel extends JMenuBar {
    private MenuListener menuListener;

    public interface MenuListener {
        void onManageSources();

        void onIndexAllSources();

        void onShowIndexingSettings();

        void onRebuildIndex();

        void onExportResults();

        void onClearHistory();

        void onToggleLiveWatch();

        void onToggleNetworkPolling();

        void onPollNow();

        void onShowUsage();

        void onExit();
        void onShowLogViewer();
    }

    public MenuBarPanel() {
        buildFileMenu();
        buildThemeMenu();
        buildHelpMenu();
    }

    private void buildFileMenu() {
        JMenu file = new JMenu("File");

        JMenuItem sourcesItem = new JMenuItem("Manage Sources...");
        sourcesItem.addActionListener(e -> {
            if (menuListener != null) menuListener.onManageSources();
        });
        file.add(sourcesItem);

        JMenuItem indexAllItem = new JMenuItem("Index All Sources");
        indexAllItem.setToolTipText("Build/update the index for all configured sources");
        indexAllItem.addActionListener(e -> {
            if (menuListener != null) menuListener.onIndexAllSources();
        });
        file.add(indexAllItem);

        JMenuItem idxSettings = new JMenuItem("Indexing Settings...");
        idxSettings.setToolTipText("Tweak parsing limits, extensions, and exclude patterns");
        idxSettings.addActionListener(e -> {
            if (menuListener != null) menuListener.onShowIndexingSettings();
        });
        file.add(idxSettings);

        JMenuItem rebuildItem = new JMenuItem("Rebuild Index (Full)");
        rebuildItem.setToolTipText("Delete and rebuild the index from all sources");
        rebuildItem.addActionListener(e -> {
            if (menuListener != null) menuListener.onRebuildIndex();
        });
        file.add(rebuildItem);

        file.addSeparator();

        JMenuItem exportCsv = new JMenuItem("Export Results to CSV...");
        exportCsv.addActionListener(e -> {
            if (menuListener != null) menuListener.onExportResults();
        });
        file.add(exportCsv);

        file.addSeparator();

        JMenuItem clearHist = new JMenuItem("Clear Search History...");
        clearHist.setToolTipText("Remove all saved queries (keeps the index intact)");
        // Java 8 的快捷键写法：getMenuShortcutKeyMask() + SHIFT_MASK
        clearHist.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE,
                Toolkit.getDefaultToolkit().getMenuShortcutKeyMask() | KeyEvent.SHIFT_MASK));
        clearHist.addActionListener(e -> {
            if (menuListener != null) menuListener.onClearHistory();
        });
        file.add(clearHist);

        file.addSeparator();

        JCheckBoxMenuItem liveWatchToggle = new JCheckBoxMenuItem("Enable Live Watch (Local)");
        liveWatchToggle.addActionListener(e -> {
            if (menuListener != null) menuListener.onToggleLiveWatch();
        });
        file.add(liveWatchToggle);

        JCheckBoxMenuItem netPollToggle = new JCheckBoxMenuItem("Enable Network Polling");
        netPollToggle.addActionListener(e -> {
            if (menuListener != null) menuListener.onToggleNetworkPolling();
        });
        file.add(netPollToggle);

        JMenuItem pollNow = new JMenuItem("Poll Network Sources Now");
        pollNow.setToolTipText("Run one-time network polling in the background");
        pollNow.addActionListener(e -> {
            if (menuListener != null) menuListener.onPollNow();
        });
        file.add(pollNow);

        file.addSeparator();

        JMenuItem exitItem = new JMenuItem("Exit");
        // Java 8：使用 getMenuShortcutKeyMask()（Win=Ctrl, macOS=Cmd）
        exitItem.setAccelerator(
                KeyStroke.getKeyStroke(KeyEvent.VK_Q, Toolkit.getDefaultToolkit().getMenuShortcutKeyMask()));

        exitItem.addActionListener(e -> {
            if (menuListener != null) menuListener.onExit();
        });

        file.add(exitItem);
        add(file);
    }

    private void buildThemeMenu() {
        add(org.abitware.docfinder.ui.ThemeUtil.buildThemeMenu());
    }

    private void buildHelpMenu() {
        JMenu help = new JMenu("Help");

        JMenuItem usage = new JMenuItem("Usage Guide");
        usage.addActionListener(e -> {
            if (menuListener != null) menuListener.onShowUsage();
        });

        JMenuItem viewLog = new JMenuItem("View Log");
        viewLog.addActionListener(e -> {
            if (menuListener != null) menuListener.onShowLogViewer();
        });
        help.add(viewLog);

        JMenuItem about = new JMenuItem("About DocFinder");
        about.addActionListener(e -> JOptionPane.showMessageDialog(this,
                "DocFinder\n\nLocal file name & content search.\n- Read-only indexing\n- Lucene + Tika\n- Java 8+\n",
                "About", JOptionPane.INFORMATION_MESSAGE));
        help.add(usage);
        help.add(about);
        add(help);
    }

    /**
     * Sets the menu listener for handling menu events.
     * 
     * @param listener the menu listener
     */
    public void setMenuListener(MenuListener listener) {
        this.menuListener = listener;
    }
}
