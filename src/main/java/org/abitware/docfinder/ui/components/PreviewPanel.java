package org.abitware.docfinder.ui.components;

import java.awt.Color;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import javax.swing.JEditorPane;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import org.abitware.docfinder.search.SearchResult;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;

/**
 * 预览面板组件，显示选中文件或文件夹的预览内容
 */
public class PreviewPanel extends JScrollPane {
    private final JEditorPane preview = new JEditorPane("text/html", "");
    private String lastPreviewInner = null;
    private String lastQuery = "";
    private final PropertyChangeListener lafListener;
    
    public PreviewPanel() {
        preview.setEditable(false);
        setViewportView(preview);
        setPreferredSize(new java.awt.Dimension(360, 560));
        
        updatePreviewInner("Preview", false);
        
        lafListener = evt -> {
            if ("lookAndFeel".equals(evt.getPropertyName())) {
                SwingUtilities.invokeLater(this::refreshPreviewForTheme);
            }
        };
        UIManager.addPropertyChangeListener(lafListener);
    }
    
    public void setPreviewContent(SearchResult result, String query) {
        this.lastQuery = query;
        
        if (result == null) {
            updatePreviewInner("No selection.");
            return;
        }
        
        Path path;
        try {
            path = Paths.get(result.path);
        } catch (Exception ex) {
            updatePreviewInner("Preview unavailable.");
            return;
        }
        
        if (Files.isDirectory(path)) {
            updatePreviewInner("Loading folder...", false);
            final Path dir = path;
            new SwingWorker<String, Void>() {
                @Override
                protected String doInBackground() {
                    return buildFolderPreviewHtml(dir, 200);
                }

                @Override
                protected void done() {
                    try {
                        updatePreviewInner(get());
                    } catch (Exception ex) {
                        updatePreviewInner("Preview failed.");
                    }
                }
            }.execute();
            return;
        }
        
        if (!Files.isRegularFile(path)) {
            updatePreviewInner("File not found.");
            return;
        }
        
        updatePreviewInner("Loading preview...", false);
        final Path target = path;
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                final int MAX_CHARS = 60_000;
                String text = loadPreviewText(target, MAX_CHARS);
                if (text == null || text.trim().isEmpty()) {
                    return "(No text content.)";
                }

                String q = (lastQuery == null) ? "" : lastQuery.trim();
                String[] terms = tokenizeForHighlight(q);
                String snippet = makeSnippet(text, terms, 300);
                String html = toHtml(snippet, terms);
                return html;
            }

            @Override
            protected void done() {
                try {
                    updatePreviewInner(get(), false);
                } catch (Exception ex) {
                    updatePreviewInner("Preview failed.");
                }
            }
        }.execute();
    }
    
    public void clearPreview() {
        updatePreviewInner("Preview", false);
    }
    
    public void updatePreviewInner(String inner) {
        updatePreviewInner(inner, true);
    }
    
    private void updatePreviewInner(String inner, boolean resetCaret) {
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
    
    private void refreshPreviewForTheme() {
        if (lastPreviewInner != null) {
            updatePreviewInner(lastPreviewInner, false);
        }
    }
    
    // 可读提取头部 N 字符（复用我们已有的 Tika 逻辑，简化为局部方法以避免循环依赖）
    private String extractTextHead(Path file, int maxChars) {
        try (InputStream is = Files.newInputStream(file, StandardOpenOption.READ)) {
            Metadata md = new Metadata();
            md.set(org.apache.tika.metadata.TikaCoreProperties.RESOURCE_NAME_KEY, file.getFileName().toString());
            org.apache.tika.parser.AutoDetectParser parser = new org.apache.tika.parser.AutoDetectParser();
            org.apache.tika.sax.BodyContentHandler handler = new org.apache.tika.sax.BodyContentHandler(maxChars);
            org.apache.tika.parser.ParseContext ctx = new org.apache.tika.parser.ParseContext();
            parser.parse(is, handler, md, ctx);
            return handler.toString();
        } catch (Throwable e) {
            return "";
        }
    }

    private String loadPreviewText(Path file, int maxChars) {
        if (file == null || maxChars <= 0) {
            return "";
        }
        String viaTika = extractTextHead(file, maxChars);
        if (viaTika != null && !viaTika.trim().isEmpty()) {
            return viaTika;
        }
        return readTextFallback(file, maxChars);
    }

    private String readTextFallback(Path file, int maxChars) {
        LinkedHashSet<Charset> candidates = new LinkedHashSet<>();
        Charset bom = detectBomCharset(file);
        if (bom != null) {
            candidates.add(bom);
        }
        candidates.add(StandardCharsets.UTF_8);
        candidates.add(StandardCharsets.UTF_16LE);
        candidates.add(StandardCharsets.UTF_16BE);
        try {
            candidates.add(Charset.forName("windows-1252"));
        } catch (Exception ignore) {
        }
        for (Charset cs : candidates) {
            if (cs == null) {
                continue;
            }
            try {
                String text = readTextWithCharset(file, maxChars, cs);
                if (text != null && !text.trim().isEmpty()) {
                    return text;
                }
            } catch (Exception ignore) {
            }
        }
        return "";
    }

    private String readTextWithCharset(Path file, int maxChars, Charset charset) throws IOException {
        if (file == null || charset == null || maxChars <= 0) {
            return "";
        }
        char[] buffer = new char[4096];
        StringBuilder sb = new StringBuilder(Math.min(maxChars, 65_536));
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(Files.newInputStream(file, StandardOpenOption.READ), charset))) {
            int remaining = maxChars;
            while (remaining > 0) {
                int n = reader.read(buffer, 0, Math.min(buffer.length, remaining));
                if (n < 0) {
                    break;
                }
                sb.append(buffer, 0, n);
                remaining -= n;
            }
        }
        if (sb.length() > 0 && sb.charAt(0) == '\uFEFF') {
            sb.deleteCharAt(0);
        }
        return sb.toString();
    }

    private Charset detectBomCharset(Path file) {
        if (file == null) {
            return null;
        }
        try (InputStream is = Files.newInputStream(file, StandardOpenOption.READ)) {
            byte[] bom = new byte[3];
            int n = is.read(bom);
            if (n >= 3 && bom[0] == (byte) 0xEF && bom[1] == (byte) 0xBB && bom[2] == (byte) 0xBF) {
                return StandardCharsets.UTF_8;
            }
            if (n >= 2) {
                if (bom[0] == (byte) 0xFE && bom[1] == (byte) 0xFF) {
                    return StandardCharsets.UTF_16BE;
                }
                if (bom[0] == (byte) 0xFF && bom[1] == (byte) 0xFE) {
                    return StandardCharsets.UTF_16LE;
                }
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    // 从查询串里提取要高亮的词（非常简化：去掉字段前缀/引号/AND/OR）
    private String[] tokenizeForHighlight(String q) {
        if (q == null)
            return new String[0];
        q = q.replaceAll("(?i)\\b(name|content|path):", " "); // 去字段前缀
        q = q.replace("\"", " ").replace("'", " ");
        q = q.replaceAll("(?i)\\bAND\\b|\\bOR\\b|\\bNOT\\b", " ");
        q = q.trim();
        if (q.isEmpty())
            return new String[0];
        // 分词：按空格分；中日文情况下直接保留整块词
        String[] arr = q.split("\\s+");
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String t : arr) {
            t = t.trim();
            if (t.isEmpty() || "*".equals(t))
                continue;
            set.add(t);
        }
        return set.toArray(new String[0]);
    }

    // 生成包含第一个命中的简单片段（上下文 window）
    private String makeSnippet(String text, String[] terms, int window) {
        if (terms.length == 0)
            return text.substring(0, Math.min(window, text.length()));
        String lower = text.toLowerCase();
        int pos = -1;
        for (String t : terms) {
            int p = lower.indexOf(t.toLowerCase());
            if (p >= 0 && (pos == -1 || p < pos))
                pos = p;
        }
        if (pos == -1)
            return text.substring(0, Math.min(window, text.length()));
        int start = Math.max(0, pos - window / 2);
        int end = Math.min(text.length(), start + window);
        return text.substring(start, end);
    }

    // 将片段转成简单 HTML 并高亮 <mark>
    private String toHtml(String snippet, String[] terms) {
        String esc = snippet.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        for (String t : terms) {
            if (t.isEmpty())
                continue;
            try {
                esc = esc.replaceAll("(?i)" + java.util.regex.Pattern.quote(t), "<mark>$0</mark>");
            } catch (Exception ignore) {
            }
        }
        return esc.replace("\n", "<br/>");
    }

    private String buildFolderPreviewHtml(Path dir, int maxEntries) {
        StringBuilder inner = new StringBuilder();
        boolean dark = isDarkColor(preview.getBackground());
        String dirColor = dark ? "#8ab4ff" : "#066";
        String metaColor = dark ? "#bbbbbb" : "#999";
        String title = (dir.getFileName() == null) ? dir.toString() : dir.getFileName().toString();
        inner.append("<h3 style='margin-top:0'>").append(htmlEscape(title)).append("</h3>");

        List<Path> entries = new ArrayList<>();
        boolean truncated = false;
        try (java.util.stream.Stream<Path> stream = Files.list(dir)) {
            Comparator<Path> comp = (a, b) -> {
                try {
                    boolean da = Files.isDirectory(a);
                    boolean db = Files.isDirectory(b);
                    if (da != db) return da ? -1 : 1;
                } catch (Exception ignore) {
                }
                String na = (a.getFileName() == null) ? a.toString() : a.getFileName().toString();
                String nb = (b.getFileName() == null) ? b.toString() : b.getFileName().toString();
                return na.compareToIgnoreCase(nb);
            };
            stream.sorted(comp).limit((long) maxEntries + 1).forEach(entries::add);
        } catch (Exception ex) {
            return "<p>" + htmlEscape("Failed to read folder: " + ex.getMessage()) + "</p>";
        }

        if (entries.size() > maxEntries) {
            truncated = true;
            entries = new ArrayList<>(entries.subList(0, maxEntries));
        }

        if (entries.isEmpty()) {
            inner.append("<p>(Empty folder)</p>");
        } else {
            inner.append("<ul style='margin:0;padding-left:16px'>");
            for (Path child : entries) {
                boolean isDir = false;
                try {
                    isDir = Files.isDirectory(child);
                } catch (Exception ignore) {
                }
                String name = (child.getFileName() == null) ? child.toString() : child.getFileName().toString();
                inner.append("<li>");
                if (isDir) {
                    inner.append(String.format("<span style='color:%s;font-weight:bold;'>[DIR]</span> ", dirColor));
                }
                inner.append(htmlEscape(name));
                if (!isDir) {
                    try {
                        long size = Files.size(child);
                        inner.append(String.format(" <span style='color:%s'>(%s)</span>", metaColor, htmlEscape(fmtSize(size))));
                    } catch (Exception ignore) {
                    }
                }
                inner.append("</li>");
            }
            inner.append("</ul>");
            if (truncated) {
                inner.append(String.format("<p style='color:%s;margin-top:8px'>(Showing first %d items)</p>", metaColor, maxEntries));
            }
        }

        return inner.toString();
    }

    private String htmlEscape(String s) {
        if (s == null) return "";
        String out = s;
        out = out.replace("&", "&amp;");
        out = out.replace("<", "&lt;");
        out = out.replace(">", "&gt;");
        out = out.replace("\"", "&quot;");
        out = out.replace("'", "&#39;");
        return out;
    }

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

    private boolean isDarkColor(Color c) {
        if (c == null) return false;
        double luminance = (0.2126 * c.getRed() + 0.7152 * c.getGreen() + 0.0722 * c.getBlue()) / 255d;
        return luminance < 0.45;
    }

    private String toCssColor(Color c) {
        if (c == null) return null;
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }
    
    private static String fmtSize(long b) {
        final long KB = 1024, MB = KB * 1024, GB = MB * 1024;
        if (b < KB)
            return b + " B";
        if (b < MB)
            return String.format("%.1f KB", b / (double) KB);
        if (b < GB)
            return String.format("%.1f MB", b / (double) MB);
        return String.format("%.1f GB", b / (double) GB);
    }
    
    public void dispose() {
        try {
            UIManager.removePropertyChangeListener(lafListener);
        } catch (Exception ignore) {
        }
    }
}