package org.abitware.docfinder;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.WindowConstants;

import org.abitware.docfinder.index.SourceManager;
import org.abitware.docfinder.search.LuceneSearchService;
import org.abitware.docfinder.search.SearchService;
import org.abitware.docfinder.ui.GlobalHotkey;
import org.abitware.docfinder.ui.MainWindow;

import com.formdev.flatlaf.FlatLightLaf;

public class App {
    public static void main(String[] args) {
        try { UIManager.setLookAndFeel(new FlatLightLaf()); }
        catch (Exception e) { try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignore) {} }

        SwingUtilities.invokeLater(() -> {
        	java.nio.file.Path indexDir = new SourceManager().getIndexDir();
            SearchService searchService = new LuceneSearchService(indexDir);
            
            MainWindow win = new MainWindow(searchService);
            win.setVisible(true);

            // 全局热键：Ctrl + Alt + Space
            GlobalHotkey ghk = new GlobalHotkey(win);
            ghk.register();
            Runtime.getRuntime().addShutdownHook(new Thread(ghk::unregister));

            // 托盘（与第2步相同）
            if (SystemTray.isSupported()) {
                try {
                    SystemTray tray = SystemTray.getSystemTray();
                    PopupMenu menu = new PopupMenu(); 
                    MenuItem openItem = new MenuItem("Open");
                    openItem.addActionListener(e -> {
                        win.setVisible(true);
                        win.setExtendedState(JFrame.NORMAL);
                        win.toFront();
                        win.requestFocus();
                    });
                    menu.add(openItem);

                    MenuItem exitItem = new MenuItem("Exit");
                    exitItem.addActionListener(e -> {
                        for (TrayIcon ti : tray.getTrayIcons()) tray.remove(ti);
                        System.exit(0);
                    });
                    menu.add(exitItem);

                    TrayIcon icon = new TrayIcon(createTrayImage(), "DocFinder", menu);
                    icon.setImageAutoSize(true);
                    icon.addMouseListener(new MouseAdapter() {
                        @Override public void mouseClicked(MouseEvent e) {
                            if (e.getButton() == MouseEvent.BUTTON1) {
                                boolean visible = win.isVisible();
                                win.setVisible(!visible);
                                if (!visible) {
                                    win.setExtendedState(JFrame.NORMAL);
                                    win.toFront();
                                    win.requestFocus();
                                }
                            }
                        }
                    });
                    tray.add(icon);
                    win.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
                } catch (Exception ex) { ex.printStackTrace(); }
            }
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
