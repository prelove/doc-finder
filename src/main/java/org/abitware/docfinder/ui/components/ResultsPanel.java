package org.abitware.docfinder.ui.components;

import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.List;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import org.abitware.docfinder.search.SearchResult;

/**
 * 结果面板组件，显示搜索结果的表格
 */
public class ResultsPanel extends JPanel {
    private final DefaultTableModel model = new DefaultTableModel(
            new Object[] { "Name", "Path", "Size", "Score", "Created", "Accessed", "Match" }, 0) {
        @Override
        public boolean isCellEditable(int r, int c) {
            return false;
        }
    };
    
    private final JTable resultTable = new JTable(model);
    private JPopupMenu rowPopup;
    private JMenuItem rememberedOpenWithItem;
    private ResultsListener resultsListener;
    
    public interface ResultsListener {
        void onSelectionChanged(SearchResult result);
        void onFileOpen(String path);
        void onFileReveal(String path);
    }
    
    public ResultsPanel() {
        setLayout(new BorderLayout());
        setupTable();
        setupPopupActions();
        setupTableShortcuts();
        add(new JScrollPane(resultTable), BorderLayout.CENTER);
    }
    
    private void setupTable() {
        resultTable.setFillsViewportHeight(true);
        resultTable.setRowHeight(22);
        resultTable.setAutoCreateRowSorter(true);

        resultTable.getColumnModel().getColumn(0).setPreferredWidth(240); // Name
        resultTable.getColumnModel().getColumn(1).setPreferredWidth(480); // Path
        resultTable.getColumnModel().getColumn(2).setPreferredWidth(90);  // Size
        resultTable.getColumnModel().getColumn(3).setPreferredWidth(70);  // Score
        resultTable.getColumnModel().getColumn(4).setPreferredWidth(130); // Created
        resultTable.getColumnModel().getColumn(5).setPreferredWidth(130); // Accessed
        resultTable.getColumnModel().getColumn(6).setPreferredWidth(110); // Match
        
        // 行选择监听
        resultTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                RowSel s = getSelectedRow();
                if (resultsListener != null) {
                    resultsListener.onSelectionChanged(createSearchResultFromRow(s));
                }
            }
        });
    }
    
    private void setupPopupActions() {
        rowPopup = new JPopupMenu();

        JMenuItem openItem = new JMenuItem("Open");
        JMenuItem revealItem = new JMenuItem("Reveal in Explorer");
        JMenu copyMenu = new JMenu("Copy");
        JMenuItem copyName = new JMenuItem("Name");
        JMenuItem copyPath = new JMenuItem("Full Path");
        copyMenu.add(copyName);
        copyMenu.add(copyPath);

        // --- Open With 子菜单 ---
        JMenu openWith = new JMenu("Open With…");
        JMenuItem chooseProg = new JMenuItem("Choose Program…");
        openWith.add(chooseProg);
        openWith.addSeparator();
        rememberedOpenWithItem = new JMenuItem("(remembered)");
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
            JFileChooser fc = new JFileChooser();
            fc.setDialogTitle("Choose a program to open this file");
            fc.setFileSelectionMode(JFileChooser.FILES_ONLY);
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                File prog = fc.getSelectedFile();
                String ext = getExtFromName(s.name);
                org.abitware.docfinder.index.ConfigManager cm = new org.abitware.docfinder.index.ConfigManager();
                cm.setOpenWithProgram(ext, prog.getAbsolutePath());
                openWithProgram(prog.getAbsolutePath(), s.path);
            }
        });

        // 其余动作（Open/Reveal/Copy）保持你之前的实现
        openItem.addActionListener(e -> {
            RowSel s = getSelectedRow();
            if (s == null)
                return;
            if (resultsListener != null) {
                resultsListener.onFileOpen(s.path);
            }
        });
        
        revealItem.addActionListener(e -> {
            RowSel s = getSelectedRow();
            if (s == null)
                return;
            if (resultsListener != null) {
                resultsListener.onFileReveal(s.path);
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

        // 右键触发：按住右键都判断
        resultTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                showPopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                showPopup(e);
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && e.getButton() == MouseEvent.BUTTON1) {
                    RowSel s = getSelectedRow();
                    if (s == null)
                        return;
                    if (resultsListener != null) {
                        resultsListener.onFileOpen(s.path);
                    }
                }
            }
        });
    }
    
    private void setupTableShortcuts() {
        InputMap im = resultTable.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        ActionMap am = resultTable.getActionMap();

        im.put(KeyStroke.getKeyStroke("ENTER"), "open");
        im.put(KeyStroke.getKeyStroke("ctrl C"), "copyPath");
        im.put(KeyStroke.getKeyStroke("ctrl shift C"), "copyName");

        am.put("open", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                RowSel s = getSelectedRow();
                if (s != null && resultsListener != null) {
                    resultsListener.onFileOpen(s.path);
                }
            }
        });
        
        am.put("copyPath", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                RowSel s = getSelectedRow();
                if (s != null)
                    setClipboard(s.path);
            }
        });
        
        am.put("copyName", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                RowSel s = getSelectedRow();
                if (s != null)
                    setClipboard(s.name);
            }
        });
    }
    
    /** 右键菜单弹出，并动态更新"记住的程序"项 */
    private void showPopup(MouseEvent e) {
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
                rememberedOpenWithItem.setText(new File(prog).getName());
                rememberedOpenWithItem.setVisible(true);
                // 重新绑定动作（先清理旧的 listener）
                for (ActionListener al : rememberedOpenWithItem.getActionListeners()) {
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
            JOptionPane.showMessageDialog(this, "Open With failed:\n" + ex.getMessage());
        }
    }
    
    private void setClipboard(String s) {
        Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(s), null);
    }
    
    private static String getExtFromName(String name) {
        int i = name.lastIndexOf('.');
        return (i > 0) ? name.substring(i + 1).toLowerCase() : "";
    }
    
    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private static boolean isMac() {
        return System.getProperty("os.name").toLowerCase().contains("mac");
    }
    
    private RowSel getSelectedRow() {
        int row = resultTable.getSelectedRow();
        if (row < 0)
            return null;
        String name = String.valueOf(resultTable.getValueAt(row, 0));
        String path = String.valueOf(resultTable.getValueAt(row, 1));
        return new RowSel(name, path);
    }
    
    private SearchResult createSearchResultFromRow(RowSel row) {
        if (row == null) return null;
        
        int selectedRow = resultTable.getSelectedRow();
        if (selectedRow < 0) return null;
        
        String name = String.valueOf(resultTable.getValueAt(selectedRow, 0));
        String path = String.valueOf(resultTable.getValueAt(selectedRow, 1));
        String sizeStr = String.valueOf(resultTable.getValueAt(selectedRow, 2));
        String scoreStr = String.valueOf(resultTable.getValueAt(selectedRow, 3));
        String createdStr = String.valueOf(resultTable.getValueAt(selectedRow, 4));
        String accessedStr = String.valueOf(resultTable.getValueAt(selectedRow, 5));
        String match = String.valueOf(resultTable.getValueAt(selectedRow, 6));
        
        // 转换数据类型
        long size = parseSize(sizeStr);
        float score = (float) parseScore(scoreStr);
        long created = parseTime(createdStr);
        long accessed = parseTime(accessedStr);
        
        return new SearchResult(name, path, score, created, accessed, match, size, false);
    }
    
    private long parseSize(String sizeStr) {
        try {
            if (sizeStr.endsWith(" B")) {
                return Long.parseLong(sizeStr.substring(0, sizeStr.length() - 2));
            } else if (sizeStr.endsWith(" KB")) {
                return (long)(Double.parseDouble(sizeStr.substring(0, sizeStr.length() - 3)) * 1024);
            } else if (sizeStr.endsWith(" MB")) {
                return (long)(Double.parseDouble(sizeStr.substring(0, sizeStr.length() - 3)) * 1024 * 1024);
            } else if (sizeStr.endsWith(" GB")) {
                return (long)(Double.parseDouble(sizeStr.substring(0, sizeStr.length() - 3)) * 1024 * 1024 * 1024);
            }
        } catch (Exception e) {
            // 忽略解析错误
        }
        return 0;
    }
    
    private double parseScore(String scoreStr) {
        try {
            return Double.parseDouble(scoreStr);
        } catch (Exception e) {
            return 0.0;
        }
    }
    
    private long parseTime(String timeStr) {
        try {
            if (timeStr == null || timeStr.trim().isEmpty()) return 0;
            return new java.text.SimpleDateFormat().parse(timeStr).getTime();
        } catch (Exception e) {
            return 0;
        }
    }
    
    public void setResults(List<SearchResult> results) {
        model.setRowCount(0);
        if (results == null) return;
        
        for (SearchResult r : results) {
            model.addRow(new Object[] { 
                r.name, r.path, fmtSize(r.sizeBytes),
                String.format("%.3f", r.score), fmtTime(r.ctime), fmtTime(r.atime),
                (r.match == null ? "" : r.match) 
            });
        }
        
        if (!results.isEmpty()) {
            resultTable.setRowSelectionInterval(0, 0);
        }
    }
    
    public void clearResults() {
        model.setRowCount(0);
        resultTable.clearSelection();
    }
    
    public int getResultCount() {
        return model.getRowCount();
    }
    
    public JTable getResultTable() {
        return resultTable;
    }
    
    public void setResultsListener(ResultsListener listener) {
        this.resultsListener = listener;
    }
    
    private static String fmtTime(long epochMs) {
        if (epochMs <= 0)
            return "";
        return new java.text.SimpleDateFormat().format(new java.util.Date(epochMs));
    }

    private static String fmtSize(long b) {
        final long KB = 1024, MB = KB * 1024, GB = MB * 1024;
        if (b < KB)
            return b + " B";
        if (b < MB)
            return String.format("%.1f KB", b / (double) KB);
        if (b < GB)
            return String.format("%.1f MB", b / (double) MB);
        return String.format("%.1f GB", b / (double) GB);
    }
    
    private static class RowSel {
        final String name, path;
        RowSel(String n, String p) {
            name = n;
            path = p;
        }
    }
}