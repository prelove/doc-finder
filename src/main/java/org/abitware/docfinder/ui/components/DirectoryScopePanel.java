package org.abitware.docfinder.ui.components;

import org.abitware.docfinder.util.PathScope;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Directory-scope selector (design D5). Layout: "Search in" combo
 * (All sources / configured sources / Custom), editable path field, Browse
 * and Clear buttons, and a scope explanation label.
 *
 * Commit semantics: the committed value is updated — and onScopeChanged
 * fired exactly once — only on Enter in the text field, a confirmed Browse
 * selection, choosing a concrete source in the combo, or Clear. Plain
 * typing, setDirectoryPath, setSourceDirectories and combo re-rendering do
 * not commit.
 */
public class DirectoryScopePanel extends JPanel {

    private static final String ALL_SOURCES = "All sources";
    private static final String CUSTOM = "Custom";

    private final Runnable onScopeChanged;
    private final JComboBox<String> sourceCombo = new JComboBox<>();
    private final JTextField pathField = new JTextField();
    private final JButton browseButton = new JButton("Browse");
    private final JButton clearButton = new JButton("Clear");
    private final JLabel hintLabel = new JLabel(
            "Includes all subfolders. Searches indexed content only.");
    private final JLabel warningLabel = new JLabel(
            "Some source entries were skipped. See Help > View Log.");

    private final List<String> sources = new ArrayList<>();
    private String committedPath = "";
    private boolean suppressComboEvents = false;

    public DirectoryScopePanel(Runnable onScopeChanged) {
        super(new BorderLayout(6, 4));
        this.onScopeChanged = onScopeChanged;
        setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));

        warningLabel.setVisible(false);

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        left.add(new JLabel("Search in:"));
        rebuildComboItems();
        sourceCombo.addActionListener(e -> onComboSelected());
        left.add(sourceCombo);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        right.add(browseButton);
        right.add(clearButton);

        pathField.setColumns(32);
        pathField.addActionListener(e -> commit(pathField.getText()));
        pathField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { refreshComboDisplay(); }
            @Override public void removeUpdate(DocumentEvent e) { refreshComboDisplay(); }
            @Override public void changedUpdate(DocumentEvent e) { refreshComboDisplay(); }
        });

        browseButton.addActionListener(e -> onBrowse());
        clearButton.addActionListener(e -> clearDirectory());

        JPanel center = new JPanel(new BorderLayout(6, 0));
        center.add(pathField, BorderLayout.CENTER);
        center.add(right, BorderLayout.EAST);

        JPanel south = new JPanel(new BorderLayout(6, 0));
        south.add(hintLabel, BorderLayout.WEST);
        south.add(warningLabel, BorderLayout.EAST);

        add(left, BorderLayout.WEST);
        add(center, BorderLayout.CENTER);
        add(south, BorderLayout.SOUTH);
    }

    /** The committed directory scope; empty string means unrestricted. */
    public String getDirectoryPath() {
        return committedPath;
    }

    /** Programmatic set: updates editing state only, never commits. */
    public void setDirectoryPath(String directoryPath) {
        committedPath = (directoryPath == null) ? "" : directoryPath;
        pathField.setText(committedPath);
        refreshComboDisplay();
    }

    /** Refresh the source list; keeps text and committed value untouched. */
    public void setSourceDirectories(List<String> directories) {
        sources.clear();
        if (directories != null) {
            for (String d : directories) {
                if (d != null && !d.trim().isEmpty()) {
                    sources.add(d);
                }
            }
        }
        rebuildComboItems();
        refreshComboDisplay();
    }

    /** Clears the scope, selects All sources, commits once. */
    public void clearDirectory() {
        committedPath = "";
        pathField.setText("");
        refreshComboDisplay();
        fireScopeChanged();
    }

    public void setScopeControlsEnabled(boolean enabled) {
        sourceCombo.setEnabled(enabled);
        pathField.setEnabled(enabled);
        browseButton.setEnabled(enabled);
        clearButton.setEnabled(enabled);
    }

    /** One-time non-modal warning about skipped source rows. */
    public void setSourceWarning(boolean hasSkippedRows) {
        warningLabel.setVisible(hasSkippedRows);
    }

    /* Exposed for tests/harness. */
    public JComboBox<String> getSourceCombo() {
        return sourceCombo;
    }

    public JTextField getPathField() {
        return pathField;
    }

    public JLabel getWarningLabel() {
        return warningLabel;
    }

    /* ------------------------------------------------------------------ */

    private void rebuildComboItems() {
        suppressComboEvents = true;
        try {
            sourceCombo.removeAllItems();
            sourceCombo.addItem(ALL_SOURCES);
            for (String s : sources) {
                sourceCombo.addItem(s);
            }
            sourceCombo.addItem(CUSTOM);
        } finally {
            suppressComboEvents = false;
        }
    }

    /**
     * Re-evaluates which combo item represents the current text: empty →
     * All sources; an equivalent configured source (compared via
     * PathScope.normalizeDirectory) → that item; anything else, including
     * invalid input, → Custom. Never reports errors or touches disk.
     */
    private void refreshComboDisplay() {
        String text = pathField.getText().trim();
        String selected;
        if (text.isEmpty()) {
            selected = ALL_SOURCES;
        } else {
            String normalized = null;
            try {
                normalized = PathScope.normalizeDirectory(text);
            } catch (IllegalArgumentException ignore) {
                // invalid while typing → Custom, no error
            }
            selected = CUSTOM;
            if (normalized != null) {
                for (String s : sources) {
                    try {
                        String sn = PathScope.normalizeDirectory(s);
                        if (normalized.equals(sn)) {
                            selected = s;
                            break;
                        }
                    } catch (IllegalArgumentException ignore) {
                        // bad configured entry cannot match
                    }
                }
            }
        }
        suppressComboEvents = true;
        try {
            sourceCombo.setSelectedItem(selected);
        } finally {
            suppressComboEvents = false;
        }
    }

    private void onComboSelected() {
        if (suppressComboEvents) {
            return;
        }
        Object sel = sourceCombo.getSelectedItem();
        if (sel == null || CUSTOM.equals(sel)) {
            return; // Custom is a display state, never a real source choice
        }
        if (ALL_SOURCES.equals(sel)) {
            clearDirectory();
            return;
        }
        commit(sel.toString());
    }

    private void onBrowse() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Choose a directory to search in");
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        String current = pathField.getText().trim();
        if (!current.isEmpty()) {
            try {
                fc.setCurrentDirectory(new File(current));
            } catch (RuntimeException ignore) {
            }
        }
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            commit(fc.getSelectedFile().getAbsolutePath());
        }
        // cancel: nothing changes
    }

    private void commit(String directoryPath) {
        committedPath = (directoryPath == null) ? "" : directoryPath.trim();
        pathField.setText(committedPath);
        refreshComboDisplay();
        fireScopeChanged();
    }

    private void fireScopeChanged() {
        if (onScopeChanged != null) {
            onScopeChanged.run();
        }
    }
}
