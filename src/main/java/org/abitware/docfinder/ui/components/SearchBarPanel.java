package org.abitware.docfinder.ui.components;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import javax.swing.*;
import org.abitware.docfinder.search.MatchMode;
import org.abitware.docfinder.search.SearchHistoryManager;
import org.abitware.docfinder.search.SearchScope;

/**
 * Top search bar panel with scope, query input, and match mode controls.
 */
public class SearchBarPanel extends JPanel {

    private final JComboBox<SearchScope> scopeBox = new JComboBox<>(SearchScope.values());
    private final JComboBox<MatchMode> matchModeBox = new JComboBox<>(MatchMode.values());
    private final JComboBox<String> queryBox = new JComboBox<>();
    private JTextField searchField;  // Not final since it's assigned in initializeComponents
    private final SearchHistoryManager historyMgr = new SearchHistoryManager();
    private final JButton toggleFiltersButton = new JButton("Filters");

    private Runnable searchCallback;
    private Runnable filterToggleCallback;

    public SearchBarPanel() {
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));

        initializeComponents();
        buildLayout();
        loadHistory();
    }

    private void initializeComponents() {
        // Scope box
        scopeBox.setToolTipText("Search scope");
        scopeBox.setMaximumRowCount(SearchScope.values().length);
        scopeBox.setSelectedItem(SearchScope.ALL);

        // Match mode box
        matchModeBox.setToolTipText("Match mode");
        matchModeBox.setMaximumRowCount(MatchMode.values().length);
        matchModeBox.setSelectedItem(MatchMode.FUZZY);

        // Editable combo box for query
        queryBox.setEditable(true);
        queryBox.setToolTipText("Tips: name:<term>, content:<term>, phrase with quotes, AND/OR, wildcard *");

        // Get editor component
        searchField = (JTextField) queryBox.getEditor().getEditorComponent();
        searchField.putClientProperty("JTextField.placeholderText",
                "Search... (e.g. report*, content:\"zero knowledge\", name:\"设计\")");

        // Enter triggers search
        searchField.addActionListener(e -> triggerSearch());

        // Selection from history also triggers search
        queryBox.addActionListener(e -> {
            Object sel = queryBox.getSelectedItem();
            if (sel != null && queryBox.isPopupVisible()) {
                setQueryText(sel.toString());
                triggerSearch();
            }
        });

        // Scope/mode changes trigger re-search if query exists
        scopeBox.addActionListener(e -> rerunIfQueryPresent());
        matchModeBox.addActionListener(e -> rerunIfQueryPresent());

        // Filter toggle button
        toggleFiltersButton.addActionListener(e -> {
            if (filterToggleCallback != null) {
                filterToggleCallback.run();
            }
        });
    }

    private void buildLayout() {
        JPanel centerStrip = new JPanel(new BorderLayout(6, 0));
        centerStrip.add(scopeBox, BorderLayout.WEST);
        centerStrip.add(queryBox, BorderLayout.CENTER);

        JPanel eastStrip = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        eastStrip.add(matchModeBox);
        eastStrip.add(toggleFiltersButton);

        add(new JLabel("🔍"), BorderLayout.WEST);
        add(centerStrip, BorderLayout.CENTER);
        add(eastStrip, BorderLayout.EAST);
    }

    private void loadHistory() {
        java.util.List<String> hist = historyMgr.load();
        for (String s : hist) {
            queryBox.addItem(s);
        }
        // Keep editor empty to show placeholder
        queryBox.setSelectedItem("");
        searchField.requestFocusInWindow();
    }

    private void triggerSearch() {
        if (searchCallback != null) {
            searchCallback.run();
        }
    }

    private void rerunIfQueryPresent() {
        String text = getQueryText();
        if (!text.isEmpty()) {
            triggerSearch();
        }
    }

    public String getQueryText() {
        return searchField.getText().trim();
    }

    public void setQueryText(String text) {
        searchField.setText(text);
    }

    public SearchScope getSelectedScope() {
        Object sel = scopeBox.getSelectedItem();
        return (sel instanceof SearchScope) ? (SearchScope) sel : SearchScope.ALL;
    }

    public MatchMode getSelectedMatchMode() {
        Object sel = matchModeBox.getSelectedItem();
        return (sel instanceof MatchMode) ? (MatchMode) sel : MatchMode.FUZZY;
    }

    public void addToHistory(String query) {
        query = (query == null) ? "" : query.trim();
        if (query.isEmpty()) return;

        java.util.List<String> latest = historyMgr.addAndSave(query);

        // Update dropdown model
        DefaultComboBoxModel<String> m = (DefaultComboBoxModel<String>) queryBox.getModel();
        m.removeAllElements();
        for (String s : latest) {
            m.addElement(s);
        }
        queryBox.setSelectedItem(query);
    }

    public void clearHistory() {
        historyMgr.save(java.util.Collections.emptyList());
        DefaultComboBoxModel<String> m = (DefaultComboBoxModel<String>) queryBox.getModel();
        m.removeAllElements();
        setQueryText("");
    }

    public void setSearchCallback(Runnable callback) {
        this.searchCallback = callback;
    }

    public void setFilterToggleCallback(Runnable callback) {
        this.filterToggleCallback = callback;
    }

    public JTextField getSearchField() {
        return searchField;
    }
}

