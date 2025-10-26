package org.abitware.docfinder.ui.components;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.text.SimpleDateFormat;
import javax.swing.*;
import org.abitware.docfinder.search.FilterState;

/**
 * Filter bar panel for extension and date range filtering.
 */
public class FilterBarPanel extends JPanel {

    private final JTextField extField = new JTextField();
    private final JFormattedTextField fromField;
    private final JFormattedTextField toField;
    private Runnable applyCallback;

    public FilterBarPanel() {
        setLayout(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));

        // Date formatter
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
        fromField = new JFormattedTextField(sdf);
        fromField.setColumns(10);
        toField = new JFormattedTextField(sdf);
        toField.setColumns(10);

        buildLayout();
        setVisible(false); // Hidden by default
    }

    private void buildLayout() {
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
        applyBtn.addActionListener(e -> {
            if (applyCallback != null) {
                applyCallback.run();
            }
        });
        row.add(applyBtn);

        add(row, BorderLayout.CENTER);
    }

    public FilterState buildFilterState() {
        FilterState f = new FilterState();
        f.exts = FilterState.parseExts(extField.getText());
        f.fromEpochMs = parseDateMs(fromField.getText());
        f.toEpochMs = parseDateMs(toField.getText());
        return f;
    }

    private Long parseDateMs(String s) {
        try {
            if (s == null || s.trim().isEmpty()) return null;
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
            sdf.setLenient(false);
            return sdf.parse(s.trim()).getTime();
        } catch (Exception e) {
            return null;
        }
    }

    public void setApplyCallback(Runnable callback) {
        this.applyCallback = callback;
    }
}

