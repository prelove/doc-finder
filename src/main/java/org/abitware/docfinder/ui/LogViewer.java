package org.abitware.docfinder.ui;

import org.abitware.docfinder.util.AppPaths;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class LogViewer extends JFrame {
    private static final int DEFAULT_TAIL_LINES = 1000;

    private final JTextArea logArea = new JTextArea();
    private final JCheckBox autoScroll = new JCheckBox("Auto-scroll", true);
    private final Path logFile = AppPaths.getBaseDir().resolve("logs").resolve("docfinder.log");

    private final JLabel tailLabel = new JLabel();
    private int tailLines = DEFAULT_TAIL_LINES;

    public LogViewer(JFrame parent) {
        setTitle("DocFinder Logs");
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setSize(900, 560);
        setLocationRelativeTo(parent);

        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        JButton refresh = new JButton("Refresh");
        refresh.addActionListener(e -> reload());

        JButton showMore = new JButton("Show More (+1000)");
        showMore.addActionListener(e -> {
            tailLines += 1000;
            reload();
        });

        JButton resetTail = new JButton("Reset Tail");
        resetTail.addActionListener(e -> {
            tailLines = DEFAULT_TAIL_LINES;
            reload();
        });

        JButton clear = new JButton("Clear");
        clear.setToolTipText("Clear current viewer text (does not delete log file)");
        clear.addActionListener(e -> logArea.setText(""));

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        top.add(refresh);
        top.add(showMore);
        top.add(resetTail);
        top.add(clear);
        top.add(autoScroll);
        top.add(tailLabel);
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
                tailLabel.setText("tail=0");
                return;
            }

            List<String> lines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
            int total = lines.size();
            int from = Math.max(0, total - tailLines);
            List<String> tail = (from <= 0) ? lines : lines.subList(from, total);
            String text = String.join(System.lineSeparator(), tail);

            if (!text.isEmpty()) {
                text += System.lineSeparator();
            }

            logArea.setText(text);
            tailLabel.setText("tail=" + tail.size() + "/" + total);

            if (autoScroll.isSelected()) {
                logArea.setCaretPosition(logArea.getDocument().getLength());
            }
        } catch (IOException ex) {
            logArea.setText("Failed to read logs: " + ex.getMessage());
            tailLabel.setText("tail=error");
        }
    }
}
