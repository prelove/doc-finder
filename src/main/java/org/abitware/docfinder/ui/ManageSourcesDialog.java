package org.abitware.docfinder.ui;

import org.abitware.docfinder.index.SourceManager;
import org.abitware.docfinder.util.Utils;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class ManageSourcesDialog extends JDialog {
    private final DefaultTableModel model = new DefaultTableModel(
            new Object[]{"Path", "Type"}, 0) {
        @Override public boolean isCellEditable(int r, int c) { return c == 1; }
    };
    private final JTable table = new JTable(model);

    public ManageSourcesDialog(Frame owner) {
        super(owner, "Index Sources", true);
        setMinimumSize(new Dimension(720, 420));
        setLocationRelativeTo(owner);

        table.setFillsViewportHeight(true);
        table.setRowHeight(22);

        // Type 列使用下拉：Local / Network
        JComboBox<String> combo = new JComboBox<>(new String[]{"Local", "Network"});
        table.getColumnModel().getColumn(1).setCellEditor(new DefaultCellEditor(combo));
        table.getColumnModel().getColumn(0).setPreferredWidth(560);
        table.getColumnModel().getColumn(1).setPreferredWidth(120);

        JScrollPane sp = new JScrollPane(table);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        JButton add = new JButton("Add...");
        JButton remove = new JButton("Remove");
        JButton detect = new JButton("Re-detect Type");
        JButton ok = new JButton("OK");
        JButton cancel = new JButton("Cancel");
        buttons.add(add); buttons.add(remove); buttons.add(detect);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 4));
        right.add(ok); right.add(cancel);

        JPanel south = new JPanel(new BorderLayout());
        south.add(buttons, BorderLayout.WEST);
        south.add(right, BorderLayout.EAST);

        getContentPane().setLayout(new BorderLayout(8,8));
        getContentPane().add(sp, BorderLayout.CENTER);
        getContentPane().add(south, BorderLayout.SOUTH);

        // 载入
        loadData();

        // 事件
        add.addActionListener(e -> onAdd());
        remove.addActionListener(e -> onRemove());
        detect.addActionListener(e -> onDetect());
        ok.addActionListener(e -> { saveData(); dispose(); });
        cancel.addActionListener(e -> dispose());
    }

 // ManageSourcesDialog.java 关键改动

    private void loadData() {
        SourceManager sm = new SourceManager();
        java.util.List<SourceManager.SourceEntry> list = sm.loadEntriesFast(); // ✅ 快速加载
        model.setRowCount(0);
        for (SourceManager.SourceEntry se : list) {
            // 先放占位，保证对话框立即弹出
            String type = se.network ? "Network" : "Local";
            model.addRow(new Object[]{ se.path, type });
        }

        // ✅ 异步检测（逐行更新）
        new javax.swing.SwingWorker<Void, int[]>() {
            @Override protected Void doInBackground() {
                for (int r = 0; r < model.getRowCount(); r++) {
                    String p = String.valueOf(model.getValueAt(r, 0));
                    boolean net = org.abitware.docfinder.util.Utils.isLikelyNetwork(java.nio.file.Paths.get(p));
                    publish(new int[]{ r, net ? 1 : 0 });
                }
                return null;
            }
            @Override protected void process(java.util.List<int[]> chunks) {
                for (int[] it : chunks) {
                    int r = it[0]; boolean net = it[1] == 1;
                    if (r >= 0 && r < model.getRowCount()) {
                        model.setValueAt(net ? "Network" : "Local", r, 1);
                    }
                }
            }
        }.execute();
    }

    private void onAdd() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Choose a folder to index");
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            String p = fc.getSelectedFile().getAbsolutePath();
            boolean net = Utils.isLikelyNetwork(Paths.get(p));
            model.addRow(new Object[]{ p, net ? "Network" : "Local" });
        }
    }

    private void onRemove() {
        int[] rows = table.getSelectedRows();
        if (rows == null || rows.length == 0) return;
        for (int i = rows.length - 1; i >= 0; i--) model.removeRow(rows[i]);
    }

    private void onDetect() {
        for (int r = 0; r < model.getRowCount(); r++) {
            String p = String.valueOf(model.getValueAt(r, 0));
            boolean net = Utils.isLikelyNetwork(Paths.get(p));
            model.setValueAt(net ? "Network" : "Local", r, 1);
        }
        JOptionPane.showMessageDialog(this, "Detection finished.");
    }

    private void saveData() {
        List<SourceManager.SourceEntry> list = new ArrayList<>();
        for (int r = 0; r < model.getRowCount(); r++) {
            String p = String.valueOf(model.getValueAt(r, 0)).trim();
            String t = String.valueOf(model.getValueAt(r, 1));
            if (!p.isEmpty()) list.add(new SourceManager.SourceEntry(p, "Network".equals(t)));
        }
        new SourceManager().saveEntries(list);
    }
}
