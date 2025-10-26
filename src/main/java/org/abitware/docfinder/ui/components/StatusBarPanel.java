package org.abitware.docfinder.ui.components;

import java.awt.BorderLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * 状态栏组件，显示应用程序状态信息
 */
public class StatusBarPanel extends JPanel {
    private final JLabel statusLabel = new JLabel("Ready");
    
    public StatusBarPanel() {
        setLayout(new BorderLayout());
        setBorder(javax.swing.BorderFactory.createEmptyBorder(4, 8, 4, 8));
        add(statusLabel, BorderLayout.WEST);
    }
    
    public void setStatus(String message) {
        statusLabel.setText(message);
    }
    
    public JLabel getStatusLabel() {
        return statusLabel;
    }
}