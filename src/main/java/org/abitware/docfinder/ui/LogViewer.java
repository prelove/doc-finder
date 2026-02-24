package org.abitware.docfinder.ui;

import org.abitware.docfinder.util.AppPaths;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class LogViewer extends JFrame {
    private final JTextArea logArea = new JTextArea();
    private final JCheckBox autoScroll = new JCheckBox("Auto-scroll", true);
    private final Path logFile = AppPaths.getBaseDir().resolve("logs").resolve("docfinder.log");

    public LogViewer(JFrame parent) {
        setTitle("DocFinder Logs");
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setSize(840, 520);
        setLocationRelativeTo(parent);

        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        JButton refresh = new JButton("Refresh");
        refresh.addActionListener(e -> reload());

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        top.add(refresh);
        top.add(autoScroll);
        top.add(new JLabel("File: " + logFile));

        add(top, BorderLayout.NORTH);
        add(new JScrollPane(logArea), BorderLayout.CENTER);

        Timer timer = new Timer(1200, e -> reload());
        timer.start();
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                timer.stop();
            }
        });

        reload();
    }

    private void reload() {
        try {
            if (!Files.exists(logFile)) {
                logArea.setText("Log file not found yet:\n" + logFile);
                return;
            }
            byte[] bytes = Files.readAllBytes(logFile);
            String text = new String(bytes, StandardCharsets.UTF_8);
            logArea.setText(text);
            if (autoScroll.isSelected()) {
                logArea.setCaretPosition(logArea.getDocument().getLength());
            }
        } catch (IOException ex) {
            logArea.setText("Failed to read logs: " + ex.getMessage());
        }
    }
}
