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
import java.awt.Dimension;
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
    private final JLabel hintLabel = new JLabel();
    private final JLabel warningLabel = new JLabel(
            "Some source entries were skipped. See Help > View Log.");

    private final JLabel searchInLabel = new JLabel("Search in:");
    private final JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
    private final List<String> sources = new ArrayList<>();
    private String committedPath = "";
    private boolean suppressComboEvents = false;
    private boolean controlsEnabled = true;
    private boolean customEditing = false;

    public DirectoryScopePanel(Runnable onScopeChanged) {
        super(new BorderLayout(6, 4));
        this.onScopeChanged = onScopeChanged;
        setBorder(BorderFactory.createEmptyBorder(4, 8, 2, 8));

        warningLabel.setVisible(false);

        JPanel left = new JPanel(new BorderLayout(6, 0));
        left.add(searchInLabel, BorderLayout.WEST);
        rebuildComboItems();
        sourceCombo.addActionListener(e -> onComboSelected());
        left.add(sourceCombo, BorderLayout.CENTER);

        right.add(browseButton);
        right.add(clearButton);

        pathField.setColumns(32);
        pathField.addActionListener(e -> commit(pathField.getText()));
        pathField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { onTextEdited(); }
            @Override public void removeUpdate(DocumentEvent e) { onTextEdited(); }
            @Override public void changedUpdate(DocumentEvent e) { onTextEdited(); }
        });

        browseButton.addActionListener(e -> onBrowse());
        clearButton.addActionListener(e -> clearDirectory());

        JPanel south = new JPanel(new BorderLayout(6, 0));
        south.add(hintLabel, BorderLayout.WEST);
        south.add(warningLabel, BorderLayout.EAST);

        JPanel center = new JPanel(new BorderLayout(0, 2));
        center.add(pathField, BorderLayout.NORTH);
        center.add(south, BorderLayout.CENTER);

        JPanel eastWrap = new JPanel(new BorderLayout());
        eastWrap.add(right, BorderLayout.NORTH);

        JPanel leftWrap = new JPanel(new BorderLayout());
        leftWrap.add(left, BorderLayout.NORTH);

        add(leftWrap, BorderLayout.WEST);
        add(center, BorderLayout.CENTER);
        add(eastWrap, BorderLayout.EAST);
        updateHint();
        applyEnabledState();
    }

    /**
     * Aligns this row with the top search bar: same label column, same combo
     * width, and an east button area as wide as the bar's east strip.
     */
    public void alignWith(int labelWidth, int comboWidth, int eastWidth) {
        searchInLabel.setPreferredSize(new Dimension(labelWidth, searchInLabel.getPreferredSize().height));
        sourceCombo.setPreferredSize(new Dimension(comboWidth, sourceCombo.getPreferredSize().height));
        right.setPreferredSize(new Dimension(eastWidth, right.getPreferredSize().height));
        revalidate();
    }

    public int getEastPreferredWidth() {
        return right.getPreferredSize().width;
    }

    public int getLabelPreferredWidth() {
        return searchInLabel.getPreferredSize().width;
    }

    /** The committed directory scope; empty string means unrestricted. */
    public String getDirectoryPath() {
        return committedPath;
    }

    /** Programmatic set: updates editing state only, never commits. */
    public void setDirectoryPath(String directoryPath) {
        committedPath = (directoryPath == null) ? "" : directoryPath;
        customEditing = !committedPath.isEmpty();
        pathField.setText(committedPath);
        refreshComboDisplay();
        updateHint();
        applyEnabledState();
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
        customEditing = false;
        pathField.setText("");
        refreshComboDisplay();
        updateHint();
        applyEnabledState();
        fireScopeChanged();
    }

    public void setScopeControlsEnabled(boolean enabled) {
        controlsEnabled = enabled;
        applyEnabledState();
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
        if (sel == null) {
            return;
        }
        if (CUSTOM.equals(sel)) {
            customEditing = true; // enables the path field; never commits by itself
            applyEnabledState();
            pathField.requestFocusInWindow();
            return;
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
        customEditing = !committedPath.isEmpty();
        pathField.setText(committedPath);
        refreshComboDisplay();
        updateHint();
        applyEnabledState();
        fireScopeChanged();
    }

    private void onTextEdited() {
        if (!pathField.getText().isEmpty()) {
            customEditing = true;
        }
        refreshComboDisplay();
        applyEnabledState();
    }

    /**
     * The hint line always shows the actually applied (committed) scope so a
     * draft being typed cannot be mistaken for the active scope (QA24-02):
     * "Applied: <path>" while the user edits something else, "All sources"
     * when unrestricted.
     */
    private void updateHint() {
        if (committedPath.isEmpty()) {
            hintLabel.setText("All sources. Searches indexed content only.");
        } else {
            hintLabel.setText("Applied: " + committedPath
                    + " — includes all subfolders. Searches indexed content only.");
        }
    }

    /** In the All-sources state there is no directory scope, so the path controls are greyed out; Custom re-enables them. */
    private void applyEnabledState() {
        boolean scoped = customEditing || !committedPath.isEmpty() || !pathField.getText().isEmpty();
        sourceCombo.setEnabled(controlsEnabled);
        pathField.setEnabled(controlsEnabled && scoped);
        browseButton.setEnabled(controlsEnabled && scoped);
        clearButton.setEnabled(controlsEnabled && scoped);
    }

    private void fireScopeChanged() {
        if (onScopeChanged != null) {
            onScopeChanged.run();
        }
    }
}
