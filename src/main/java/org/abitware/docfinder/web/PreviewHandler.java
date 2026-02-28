package org.abitware.docfinder.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Map;

/**
 * 处理 GET /api/preview 请求，返回文件的文本预览（最多 8 KB）。
 * 查询参数:
 *   path – 文件的绝对路径（必填）
 *
 * 安全说明: 仅允许读取路径以 '/' 开头的有效绝对路径，且文件必须存在。
 * 服务默认绑定 localhost，因此不对外暴露；路径遍历防护仍然是必要的。
 */
class PreviewHandler implements HttpHandler {

    /** 预览返回的最大字节数 */
    private static final int MAX_PREVIEW_BYTES = 8 * 1024;

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            SearchHandler.sendText(exchange, 405, "Method Not Allowed");
            return;
        }

        Map<String, String> params = SearchHandler.parseQuery(exchange.getRequestURI());
        String pathStr = params.get("path");
        if (pathStr == null || pathStr.trim().isEmpty()) {
            SearchHandler.sendJson(exchange, 400, "{\"error\":\"Missing 'path' parameter\"}");
            return;
        }

        // 安全检查: 原始输入不允许包含路径穿越序列 (..)
        if (pathStr.contains("..")) {
            SearchHandler.sendJson(exchange, 400, "{\"error\":\"Invalid path\"}");
            return;
        }

        Path filePath;
        try {
            filePath = Paths.get(pathStr).toAbsolutePath().normalize();
        } catch (Exception e) {
            SearchHandler.sendJson(exchange, 400, "{\"error\":\"Invalid path\"}");
            return;
        }

        if (!Files.exists(filePath)) {
            SearchHandler.sendJson(exchange, 404, "{\"error\":\"File not found\"}");
            return;
        }
        if (Files.isDirectory(filePath)) {
            String name = filePath.getFileName() != null ? filePath.getFileName().toString() : filePath.toString();
            String json = "{\"path\":\"" + SearchHandler.escapeJson(filePath.toString()) + "\","
                    + "\"name\":\"" + SearchHandler.escapeJson(name) + "\","
                    + "\"directory\":true,\"text\":\"[Directory]\"}";
            SearchHandler.sendJson(exchange, 200, json);
            return;
        }

        // 读取文件头部内容
        byte[] buf = new byte[MAX_PREVIEW_BYTES];
        int read = 0;
        try (InputStream is = Files.newInputStream(filePath, StandardOpenOption.READ)) {
            read = is.read(buf, 0, buf.length);
        } catch (IOException e) {
            SearchHandler.sendJson(exchange, 500, "{\"error\":\"Cannot read file: " + SearchHandler.escapeJson(e.getMessage()) + "\"}");
            return;
        }

        if (read <= 0) {
            String json = "{\"path\":\"" + SearchHandler.escapeJson(filePath.toString()) + "\","
                    + "\"text\":\"\",\"truncated\":false}";
            SearchHandler.sendJson(exchange, 200, json);
            return;
        }

        // 二进制探测：前 512 字节含 NUL 则标为 binary
        int probe = Math.min(read, 512);
        boolean binary = false;
        for (int i = 0; i < probe; i++) {
            if (buf[i] == 0) { binary = true; break; }
        }

        if (binary) {
            SearchHandler.sendJson(exchange, 200,
                    "{\"path\":\"" + SearchHandler.escapeJson(filePath.toString()) + "\","
                    + "\"text\":\"[Binary file – no text preview]\",\"truncated\":false}");
            return;
        }

        Charset cs = detectCharset(buf, read);
        String text = new String(buf, 0, read, cs);
        boolean truncated = Files.size(filePath) > MAX_PREVIEW_BYTES;

        String json = "{\"path\":\"" + SearchHandler.escapeJson(filePath.toString()) + "\","
                + "\"text\":\"" + SearchHandler.escapeJson(text) + "\","
                + "\"truncated\":" + truncated + "}";
        SearchHandler.sendJson(exchange, 200, json);
    }

    /** 简单的 UTF-8 / UTF-16 / Latin-1 字符集探测 */
    private static Charset detectCharset(byte[] buf, int len) {
        // BOM 检测
        if (len >= 3 && (buf[0] & 0xFF) == 0xEF && (buf[1] & 0xFF) == 0xBB && (buf[2] & 0xFF) == 0xBF) {
            return StandardCharsets.UTF_8;
        }
        if (len >= 2 && (buf[0] & 0xFF) == 0xFF && (buf[1] & 0xFF) == 0xFE) {
            return StandardCharsets.UTF_16LE;
        }
        if (len >= 2 && (buf[0] & 0xFF) == 0xFE && (buf[1] & 0xFF) == 0xFF) {
            return StandardCharsets.UTF_16BE;
        }
        // 尝试 UTF-8 有效性检查（简单计数法）
        try {
            new String(buf, 0, len, StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8);
            return StandardCharsets.UTF_8;
        } catch (Exception e) {
            return StandardCharsets.ISO_8859_1;
        }
    }
}
