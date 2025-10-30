package org.abitware.docfinder;

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import javax.swing.*;

import org.abitware.docfinder.index.SourceManager;
import org.abitware.docfinder.search.LuceneSearchService;
import org.abitware.docfinder.search.SearchService;
import org.abitware.docfinder.ui.GlobalHotkey;
import org.abitware.docfinder.ui.MainWindow;
import org.abitware.docfinder.ui.ThemeUtil;
import org.abitware.docfinder.util.SingleInstance;

public class App {
    private static volatile MainWindow MAIN;          // 供 ACTIVATE 回调使用
    private static final String APP_ID = "org.abitware.docfinder"; // 影响端口计算

    public static void main(String[] args) {
        // 1) 主题（必须在任何 Swing 组件创建前）
        ThemeUtil.initLafOnStartup();

        // 2) 单实例：若已有实例，发送 ACTIVATE 并退出
        SingleInstance instance = SingleInstance.tryAcquire(APP_ID, cmd -> bringToFront());
        if (instance == null) return; // 次实例：已通知老实例激活，直接退出

        SwingUtilities.invokeLater(() -> {
            try {
                java.nio.file.Path indexDir = new SourceManager().getIndexDir();
                SearchService searchService = new LuceneSearchService(indexDir);

                MainWindow win = new MainWindow(searchService);
                MAIN = win; // 赋值供回调 bringToFront 使用
                win.setVisible(true);

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
                        openItem.addActionListener(e -> {
                            bringToFront();
                        });
                        menu.add(openItem);

                        MenuItem exitItem = new MenuItem("Exit");
                        exitItem.addActionListener(e -> {
                            // 退出前清理托盘与单实例
                            try {
                                for (TrayIcon ti : tray.getTrayIcons()) tray.remove(ti);
                            } catch (Exception ignore) {}
                            try { instance.close(); } catch (Exception ignore) {}
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
                        ex.printStackTrace();
                    }
                }

                // JVM 退出时关闭单实例监听
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    try { instance.close(); } catch (Exception ignore) {}
                }));
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(null,
                        "Failed to initialize search service: " + ex.getMessage() + "\nApplication will exit.",
                        "Initialization Error",
                        JOptionPane.ERROR_MESSAGE);
                System.exit(1);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(null,
                        "An unexpected error occurred during application startup: " + ex.getMessage() + "\nApplication will exit.",
                        "Initialization Error",
                        JOptionPane.ERROR_MESSAGE);
                ex.printStackTrace();
                System.exit(1);
            }
        });
    }

    /** 把主窗口前置显示 */
    private static void bringToFront() {
        SwingUtilities.invokeLater(() -> {
            MainWindow w = MAIN;
            if (w == null) return;
            if (!w.isVisible()) w.setVisible(true);
            w.setExtendedState(JFrame.NORMAL);
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