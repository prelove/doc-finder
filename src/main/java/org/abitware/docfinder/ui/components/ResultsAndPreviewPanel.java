package org.abitware.docfinder.ui.components;

import org.abitware.docfinder.search.SearchResult;
import org.abitware.docfinder.ui.MainWindow;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.beans.PropertyChangeListener;
import java.util.Collections;
import java.util.List;

/**
 * Panel responsible for displaying search results in a table and a preview of the selected item.
 */
public class ResultsAndPreviewPanel extends JPanel {
    private final JTable resultTable;
    private final JEditorPane preview;
    private final DefaultTableModel model;
    private String lastPreviewInner = null;
    private final PropertyChangeListener lafListener;
    private JSplitPane split;

    public ResultsAndPreviewPanel(MainWindow mainWindow) {
        super(new BorderLayout());

        model = new DefaultTableModel(
                new Object[]{"Name", "Path", "Size", "Score", "Created", "Accessed", "Match"}, 0) {
            @Override
            public boolean isCellEditable(int r, int c) {
                return false;
            }
        };

        resultTable = new JTable(model);
        resultTable.setFillsViewportHeight(true);
        resultTable.setRowHeight(22);
        resultTable.setAutoCreateRowSorter(true);

        resultTable.getColumnModel().getColumn(0).setPreferredWidth(240); // Name
        resultTable.getColumnModel().getColumn(1).setPreferredWidth(480); // Path
        resultTable.getColumnModel().getColumn(2).setPreferredWidth(90);  // Size
        resultTable.getColumnModel().getColumn(3).setPreferredWidth(70); // Score
        resultTable.getColumnModel().getColumn(4).setPreferredWidth(130); // Created
        resultTable.getColumnModel().getColumn(5).setPreferredWidth(130); // Accessed
        resultTable.getColumnModel().getColumn(6).setPreferredWidth(110); // Match

        JScrollPane center = new JScrollPane(resultTable);

        preview = new JEditorPane("text/html", "");
        preview.setEditable(false);
        JScrollPane right = new JScrollPane(preview);
        right.setPreferredSize(new Dimension(360, 560));

        split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, center, right);
        split.setResizeWeight(0.72); // Left 72% / Right 28%
        add(split, BorderLayout.CENTER);

        // Listener for Look and Feel changes to refresh preview
        lafListener = evt -> {
            if ("lookAndFeel".equals(evt.getPropertyName())) {
                SwingUtilities.invokeLater(this::refreshPreviewForTheme);
            }
        };
        UIManager.addPropertyChangeListener(lafListener);

        // Add table selection listener to load preview asynchronously
        // The listener will be set by MainWindow
    }

    /**
     * Populates the results table with search results.
     * @param query The search query.
     * @param list The list of search results.
     * @param elapsedMs The time taken for the search in milliseconds.
     */
    public void populateResults(String query, List<SearchResult> list, long elapsedMs) {
        if (list == null) {
            list = Collections.emptyList();
        }

        model.setRowCount(0);
        for (SearchResult r : list) {
            model.addRow(new Object[]{r.name, r.path, r.sizeBytes,
                    String.format("%.3f", r.score), r.ctime, r.atime,
                    (r.match == null ? "" : r.match)});
        }

        if (list.isEmpty()) {
            // statusLabel.setText(String.format("No results. | %d ms", elapsedMs)); // Handled by MainWindow
            resultTable.clearSelection();
            updatePreviewInner("No results.");
        } else {
            // statusLabel.setText(String.format("Results: %d  |  %d ms", list.size(), elapsedMs)); // Handled by MainWindow
            resultTable.setRowSelectionInterval(0, 0);
        }
    }

    /**
     * Updates the inner HTML content of the preview pane.
     * @param inner The HTML string to display.
     */
    public void updatePreviewInner(String inner) {
        updatePreviewInner(inner, true);
    }

    /**
     * Updates the inner HTML content of the preview pane.
     * @param inner The HTML string to display.
     * @param resetCaret Whether to reset the caret position to the beginning.
     */
    public void updatePreviewInner(String inner, boolean resetCaret) {
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

    /**
     * Refreshes the preview content for theme changes.
     */
    private void refreshPreviewForTheme() {
        if (lastPreviewInner != null) {
            updatePreviewInner(lastPreviewInner, false);
        }
    }

    /**
     * Wraps the given HTML content with basic styling for the preview pane.
     * @param inner The inner HTML content.
     * @return The full HTML content with styling.
     */
    private String htmlWrap(String inner) {
        Color bg = preview.getBackground();
        Color fg = preview.getForeground();
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

    /**
     * Converts a Color object to a CSS color string.
     * @param c The Color object.
     * @return The CSS color string (e.g., "#RRGGBB").
     */
    private String toCssColor(Color c) {
        if (c == null) return null;
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    /**
     * Checks if a given color is considered 'dark' for theme purposes.
     * @param c The Color object.
     * @return True if the color is dark, false otherwise.
     */
    private boolean isDarkColor(Color c) {
        if (c == null) return false;
        double luminance = (0.2126 * c.getRed() + 0.7152 * c.getGreen() + 0.0722 * c.getBlue()) / 255d;
        return luminance < 0.45;
    }

    /**
     * Returns the currently selected row in the results table.
     * @return A RowSel object representing the selected row, or null if no row is selected.
     */
    public RowSel getSelectedRow() {
        int row = resultTable.getSelectedRow();
        if (row < 0)
            return null;
        String name = String.valueOf(resultTable.getValueAt(row, 0));
        String path = String.valueOf(resultTable.getValueAt(row, 1));
        return new RowSel(name, path);
    }

    /**
     * Helper class to hold selected row data.
     */
    public static class RowSel {
        public final String name, path;

        public RowSel(String n, String p) {
            name = n;
            path = p;
        }
    }

    // Delegate methods for MainWindow to call
    public JTable getResultTable() {
        return resultTable;
    }

    public JEditorPane getPreviewPane() {
        return preview;
    }

    public DefaultTableModel getTableModel() {
        return model;
    }

    /**
     * Interface for listening to table row selection changes.
     */
    public interface TableSelectionListener {
        void onRowSelected(int selectedRow);
    }

    private TableSelectionListener tableSelectionListener;

    /**
     * Sets the listener for table row selection changes.
     * @param listener The listener to set.
     */
    public void setTableSelectionListener(TableSelectionListener listener) {
        this.tableSelectionListener = listener;
    }
}
