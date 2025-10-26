package org.abitware.docfinder.ui.components;

import java.awt.BorderLayout;
import javax.swing.*;

/**
 * Simple status bar panel to display status messages.
 */
public class StatusBarPanel extends JPanel {

    private final JLabel statusLabel = new JLabel("Ready");

    public StatusBarPanel() {
        setLayout(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        add(statusLabel, BorderLayout.WEST);
    }

    public void setText(String text) {
        statusLabel.setText(text);
    }

    public String getText() {
        return statusLabel.getText();
    }

    public JLabel getLabel() {
        return statusLabel;
    }
}

