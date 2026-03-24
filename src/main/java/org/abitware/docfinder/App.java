package org.abitware.docfinder;

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import javax.swing.*;

import org.abitware.docfinder.index.ConfigManager;
import org.abitware.docfinder.index.SourceManager;
import org.abitware.docfinder.search.LuceneSearchService;
import org.abitware.docfinder.search.SearchService;
import org.abitware.docfinder.ui.GlobalHotkey;
import org.abitware.docfinder.ui.MainWindow;
import org.abitware.docfinder.ui.ThemeUtil;
import org.abitware.docfinder.util.LegacyMigration;
import org.abitware.docfinder.util.SingleInstance;
import org.abitware.docfinder.web.WebServer;
import org.abitware.docfinder.web.KkFileViewServer;

import org.slf4j.Logger; // ADDED
import org.slf4j.LoggerFactory; // ADDED

public class App {
    private static volatile MainWindow MAIN;          // 䧛 ACTIVATE 回调使用
    private static final String APP_ID = "org.abitware.docfinder"; // 影响端口计算
    private static final Logger logger = LoggerFactory.getLogger(App.class); // ADDED LOGGER

    public static void main(String[] args) {
        // 1) 主题（必须在任何 Swing 组件创建前）
        ThemeUtil.initLafOnStartup();

        // 2) 单实例：若已有实例，发送 ACTIVATE 并退出
        SingleInstance instance = SingleInstance.tryAcquire(APP_ID, cmd -> bringToFront());
        if (instance == null) return; // 次实例：已通知老实例激活，直接退出

        // 3) One-time legacy data migration (~/.docfinder → ./.docfinder) off the EDT
        if (LegacyMigration.needsMigration()) {
            new Thread(() -> {
                int count = LegacyMigration.migrate();
                if (count > 0) {
                    logger.info("Migrated {} file(s) from legacy ~/.docfinder to app data directory.", count);
                }
            }, "docfinder-migration").start();
        }

        // 4) Optionally start kkFileView server (before web interface)
        ConfigManager cfg = new ConfigManager();
        final KkFileViewServer kkFileViewServer;
        if (cfg.isKkFileViewEnabled()) {
            kkFileViewServer = new KkFileViewServer(cfg.getKkFileViewPort());
            if (kkFileViewServer.isAvailable()) {
                try {
                    kkFileViewServer.start();
                    logger.info("kkFileView server started at {}", kkFileViewServer.getBaseUrl());
                } catch (IOException ex) {
                    logger.error("Failed to start kkFileView server", ex);
                }
                Runtime.getRuntime().addShutdownHook(new Thread(kkFileViewServer::stop, "docfinder-kkfileview-stop"));
            } else {
                logger.warn("kkFileView is enabled but JAR not found at: {}", kkFileViewServer.getJarPath());
                logger.warn("Please download kkFileView JAR and place it at the above location.");
            }
        } else {
            kkFileViewServer = null;
        }

        // 5) Optionally start web interface (before EDT to get config early)
        final WebServer webServer;
        if (cfg.isWebEnabled()) {
            webServer = new WebServer(cfg.getWebPort(), cfg.getWebBindAddress());
            try {
                webServer.start();
            } catch (IOException ex) {
                logger.error("Failed to start web interface", ex);
            }
            Runtime.getRuntime().addShutdownHook(new Thread(webServer::stop, "docfinder-web-stop"));
        } else {
            webServer = null;
        }

        // 6) Show window on the EDT, then open the search index in a background thread to avoid EDT blocking
        SwingUtilities.invokeLater(() -> {
            // Create the window first with a null search service so the UI is immediately responsive
            MainWindow win = new MainWindow(null);
            MAIN = win;
            // Pass the web server and kkFileView server references so the menu can toggle them
            win.setWebServer(webServer);
            win.setKkFileViewServer(kkFileViewServer);
            win.setVisible(true);

            // Open the Lucene index off the EDT to avoid blocking Swing painting
            new Thread(() -> {
                try {
                    java.nio.file.Path indexDir = new SourceManager().getIndexDir();
                    ConfigManager cm = new ConfigManager();
                    org.abitware.docfinder.index.IndexSettings idxSettings = cm.loadIndexSettings();
                    SearchService searchService = new LuceneSearchService(indexDir, idxSettings);
                    // Web interface gets a reference to the search service once index is ready
                    if (webServer != null) webServer.setSearchService(searchService);
                    SwingUtilities.invokeLater(() -> win.setSearchService(searchService));
                } catch (IOException ex) {
                    logger.error("Failed to open search index", ex);
                    SwingUtilities.invokeLater(() ->
                        win.getStatusLabel().setText("Search index unavailable: " + ex.getMessage()));
                }
            }, "docfinder-index-open").start();

            // 全局热键：Ctrl + Alt + Space
            GlobalHotkey ghk = new GlobalHotkey(win);
            ghk.register();
            Runtime.getRuntime().addShutdownHook(new Thread(ghk::unregister));

            // 托盘
            if (SystemTray.isSupported()) {
                try {
                    SystemTray tray = SystemTray.getSystemTray();
                    PopupMenu menu = new PopupMenu();
                    MenuItem openItem = new MenuItem("Open");
                    openItem.addActionListener(e -> bringToFront());
                    menu.add(openItem);

                    MenuItem exitItem = new MenuItem("Exit");
                    exitItem.addActionListener(e -> {
                        try {
                            for (TrayIcon ti : tray.getTrayIcons()) tray.remove(ti);
                        } catch (Exception ex) { logger.error("Error removing tray icon", ex); }
                        try { instance.close(); } catch (Exception ex) { logger.error("Error closing single instance lock", ex); }
                        System.exit(0);
                    });
                    menu.add(exitItem);

                    TrayIcon icon = new TrayIcon(createTrayImage(), "DocFinder", menu);
                    icon.setImageAutoSize(true);
                    icon.addMouseListener(new MouseAdapter() {
                        @Override public void mouseClicked(MouseEvent e) {
                            if (e.getButton() == MouseEvent.BUTTON1) bringToFront();
                        }
                    });
                    tray.add(icon);
                    win.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
                } catch (Exception ex) {
                    logger.error("Error setting up system tray", ex);
                }
            }

            // JVM 退出时关闭单实例监听
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { instance.close(); } catch (Exception ex) { logger.error("Error closing single instance lock on shutdown", ex); }
            }));
        });
    }

    /** 把主窗口前置显示 */
    private static void bringToFront() {
        SwingUtilities.invokeLater(() -> {
            MainWindow w = MAIN;
            if (w == null) return;
            if (!w.isVisible()) w.setVisible(true);
            int state = w.getExtendedState();
            w.setExtendedState(state & ~JFrame.ICONIFIED);
            w.toFront();
            w.requestFocus();
        });
    }

    public static Image createTrayImage() {
        int s = 16;
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0x3B82F6)); g.fillRoundRect(0, 0, s-1, s-1, 4, 4);
        g.setColor(Color.WHITE); g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 9));
        g.drawString("DF", 2, 12);
        g.dispose();
        return img;
    }
}
