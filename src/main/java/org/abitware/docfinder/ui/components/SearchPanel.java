package org.abitware.docfinder.ui.components;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.KeyEvent;
import java.util.List;
import javax.swing.*;
import org.abitware.docfinder.search.FilterState;
import org.abitware.docfinder.search.MatchMode;
import org.abitware.docfinder.search.SearchHistoryManager;
import org.abitware.docfinder.search.SearchScope;

/**
 * 搜索面板组件，包含搜索输入框、范围选择、匹配模式选择和过滤器
 */
public class SearchPanel extends JPanel {
    private final JComboBox<SearchScope> scopeBox = new JComboBox<>(SearchScope.values());
    private final JComboBox<MatchMode> matchModeBox = new JComboBox<>(MatchMode.values());
    private final JComboBox<String> queryBox = new JComboBox<>();
    private JTextField searchField;
    private final SearchHistoryManager historyMgr = new SearchHistoryManager();
    
    // 过滤器组件
    private final JTextField extField = new JTextField();
    private JFormattedTextField fromField;
    private JFormattedTextField toField;
    private final JPanel filterBar = new JPanel(new BorderLayout(6, 6));
    
    private SearchListener searchListener;
    
    public interface SearchListener {
        void onSearch(String query, FilterState filters, SearchScope scope, MatchMode matchMode);
    }
    
    public SearchPanel() {
        setLayout(new BorderLayout());
        add(buildTopBar(), BorderLayout.NORTH);
        add(buildFilterBar(), BorderLayout.CENTER);
        
        initializeComponents();
    }
    
    private void initializeComponents() {
        scopeBox.setToolTipText("Search scope");
        scopeBox.setMaximumRowCount(SearchScope.values().length);
        scopeBox.setSelectedItem(SearchScope.ALL);

        matchModeBox.setToolTipText("Match mode");
        matchModeBox.setMaximumRowCount(MatchMode.values().length);
        matchModeBox.setSelectedItem(MatchMode.FUZZY);

        // 可编辑下拉框
        queryBox.setEditable(true);
        queryBox.setToolTipText("Tips: name:<term>, content:<term>, phrase with quotes, AND/OR, wildcard *");

        // 取到 editor 的 JTextField 以便设置 placeholder 和监听回车
        searchField = (JTextField) queryBox.getEditor().getEditorComponent();
        searchField.putClientProperty("JTextField.placeholderText",
                "Search... (e.g. report*, content:\"zero knowledge\", name:\"设计\")");
        // 回车触发搜索
        searchField.addActionListener(e -> performSearch());

        // 下拉选择某条历史时也触发搜索
        queryBox.addActionListener(e -> {
            Object sel = queryBox.getSelectedItem();
            if (sel != null && queryBox.isPopupVisible()) {
                setQueryText(sel.toString());
                performSearch();
            }
        });

        // 首次加载历史
        List<String> hist = historyMgr.load();
        for (String s : hist)
            queryBox.addItem(s);

        // 关键：保持编辑器为空，placeholder 才会显示
        queryBox.setSelectedItem("");
        searchField.requestFocusInWindow();
        
        // 事件监听
        scopeBox.addActionListener(e -> rerunIfQueryPresent());
        matchModeBox.addActionListener(e -> rerunIfQueryPresent());
    }
    
    private JComponent buildTopBar() {
        JPanel top = new JPanel(new BorderLayout(8, 8));
        top.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));

        JButton toggleFilters = new JButton("Filters");
        toggleFilters.addActionListener(e -> filterBar.setVisible(!filterBar.isVisible()));

        JPanel centerStrip = new JPanel(new BorderLayout(6, 0));
        centerStrip.add(scopeBox, BorderLayout.WEST);
        centerStrip.add(queryBox, BorderLayout.CENTER);

        JPanel eastStrip = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        eastStrip.add(matchModeBox);
        eastStrip.add(toggleFilters);

        top.add(new JLabel("🔍"), BorderLayout.WEST);
        top.add(centerStrip, BorderLayout.CENTER);
        top.add(eastStrip, BorderLayout.EAST);
        return top;
    }
    
    private JComponent buildFilterBar() {
        // 日期格式器
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd");
        fromField = new JFormattedTextField(sdf);
        fromField.setColumns(10);
        toField = new JFormattedTextField(sdf);
        toField.setColumns(10);

        JPanel row = new JPanel();
        row.setLayout(new FlowLayout(FlowLayout.LEFT, 8, 4));

        row.add(new JLabel("Ext(s):"));
        extField.setColumns(16);
        extField.setToolTipText("Comma-separated, e.g. pdf,docx,txt");
        row.add(extField);

        row.add(new JLabel("From:"));
        row.add(fromField);
        row.add(new JLabel("To:"));
        row.add(toField);

        JButton applyBtn = new JButton("Apply");
        applyBtn.addActionListener(e -> performSearch());
        row.add(applyBtn);

        filterBar.add(row, BorderLayout.CENTER);
        filterBar.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        filterBar.setVisible(false); // 默认折叠
        return filterBar;
    }
    
    private void performSearch() {
        if (searchListener != null) {
            String query = getQueryText();
            FilterState filters = buildFilterState();
            SearchScope scope = getSelectedScope();
            MatchMode matchMode = getSelectedMatchMode();
            
            searchListener.onSearch(query, filters, scope, matchMode);
        }
    }
    
    private void rerunIfQueryPresent() {
        String text = getQueryText();
        if (!text.isEmpty()) {
            performSearch();
        }
    }
    
    private String getQueryText() {
        return (searchField == null) ? "" : searchField.getText().trim();
    }
    
    private void setQueryText(String s) {
        if (searchField != null)
            searchField.setText(s);
    }
    
    private SearchScope getSelectedScope() {
        Object sel = scopeBox.getSelectedItem();
        return (sel instanceof SearchScope) ? (SearchScope) sel : SearchScope.ALL;
    }

    private MatchMode getSelectedMatchMode() {
        Object sel = matchModeBox.getSelectedItem();
        return (sel instanceof MatchMode) ? (MatchMode) sel : MatchMode.FUZZY;
    }
    
    private FilterState buildFilterState() {
        FilterState f = new FilterState();
        f.exts = FilterState.parseExts(extField.getText());
        f.fromEpochMs = parseDateMs(fromField.getText());
        f.toEpochMs = parseDateMs(toField.getText());
        return f;
    }
    
    private Long parseDateMs(String s) {
        try {
            if (s == null || s.trim().isEmpty())
                return null;
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd");
            sdf.setLenient(false);
            return sdf.parse(s.trim()).getTime();
        } catch (Exception e) {
            return null;
        }
    }
    
    public void setSearchListener(SearchListener listener) {
        this.searchListener = listener;
    }
    
    public void addToHistory(String query) {
        query = (query == null) ? "" : query.trim();
        if (query.isEmpty())
            return;

        List<String> latest = historyMgr.addAndSave(query);

        // 更新下拉框模型：去重置顶、最多100
        DefaultComboBoxModel<String> m = (DefaultComboBoxModel<String>) queryBox.getModel();
        // 简单做法：清空重加，100项以内都无感
        m.removeAllElements();
        for (String s : latest)
            m.addElement(s);
        queryBox.setSelectedItem(query); // 置顶显示
    }
    
    public void clearHistory() {
        historyMgr.save(java.util.Collections.emptyList());
        DefaultComboBoxModel<String> m = (DefaultComboBoxModel<String>) queryBox.getModel();
        m.removeAllElements();
        setQueryText("");
    }
    
    public JTextField getSearchField() {
        return searchField;
    }
    
    public void requestSearchFocus() {
        if (searchField != null) {
            searchField.requestFocusInWindow();
        }
    }
}