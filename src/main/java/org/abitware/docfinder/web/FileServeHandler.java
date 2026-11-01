package org.abitware.docfinder.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Locale;
import java.util.Map;

/**
 * Serves binary files and UTF-8 text at {@code GET /api/file?path=<encoded-abs-path>}.
 *
 * <p>Used by the web search UI to feed files into the jit-viewer previewer.
 * The endpoint is intentionally available only when the web server is running;
 * callers from outside the local machine can only reach it if the admin
 * configured the server to bind to a non-loopback address.
 *
 * <p>Security: path-traversal sequences ("{@code ..}") are rejected; the
 * resolved path must be absolute. Binary/large files are served without
 * truncation so the viewer can render them correctly.
 */
class FileServeHandler implements HttpHandler {

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

        if (!Files.exists(filePath) || Files.isDirectory(filePath)) {
            SearchHandler.sendJson(exchange, 404, "{\"error\":\"File not found\"}");
            return;
        }

        serveFile(exchange, filePath, null);
    }

    /**
     * Sends binary bytes or UTF-8 text with an appropriate Content-Type and optional
     * Content-Disposition header.
     */
    static void serveFile(HttpExchange exchange, Path filePath, String downloadName) throws IOException {
        String mime = guessMime(filePath.getFileName().toString());
        long size = Files.size(filePath);

        boolean textContent = mime.startsWith("text/");
        Charset detected = null;
        if (textContent) {
            byte[] sample;
            try (InputStream in = Files.newInputStream(filePath)) {
                sample = in.readNBytes(64 * 1024);
            }
            detected = TextServeHandler.detectCharset(sample, sample.length);
            // Java 字符集名未必是浏览器支持的标签；统一转为 UTF-8，响应头才能准确描述正文。
            mime = mime.split(";", 2)[0] + "; charset=utf-8";
        }

        exchange.getResponseHeaders().add("Content-Type", mime);
        if (downloadName != null) {
            exchange.getResponseHeaders().add("Content-Disposition",
                    "attachment; filename=\"" + downloadName.replace("\"", "'") + "\"");
        }
        exchange.getResponseHeaders().add("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, textContent ? 0 : size);
        if (textContent) {
            // 流式转码避免把整个大文本文件载入内存；长度由 HTTP 分块传输确定。
            try (Reader reader = new BufferedReader(new InputStreamReader(Files.newInputStream(filePath), detected));
                 Writer writer = new BufferedWriter(new OutputStreamWriter(exchange.getResponseBody(), StandardCharsets.UTF_8))) {
                int first = reader.read();
                if (first >= 0 && first != '\uFEFF') {
                    writer.write(first);
                }
                char[] buffer = new char[8192];
                int count;
                while ((count = reader.read(buffer)) != -1) {
                    writer.write(buffer, 0, count);
                }
            }
            return;
        }
        try (OutputStream out = exchange.getResponseBody();
             InputStream in = Files.newInputStream(filePath)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
    }

    /** Very small MIME-type table; covers the formats jit-viewer supports. */
    static String guessMime(String filename) {
        if (filename == null) return "application/octet-stream";
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pdf"))  return "application/pdf";
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (lower.endsWith(".doc"))  return "application/msword";
        if (lower.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (lower.endsWith(".xls"))  return "application/vnd.ms-excel";
        if (lower.endsWith(".pptx")) return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        if (lower.endsWith(".ppt"))  return "application/vnd.ms-powerpoint";
        if (lower.endsWith(".ofd"))  return "application/ofd";
        if (lower.endsWith(".txt"))  return "text/plain";
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) return "text/markdown; charset=utf-8";
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html; charset=utf-8";
        if (lower.endsWith(".css"))  return "text/css; charset=utf-8";
        if (lower.endsWith(".js"))   return "application/javascript; charset=utf-8";
        if (lower.endsWith(".json")) return "application/json; charset=utf-8";
        if (lower.endsWith(".png"))  return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif"))  return "image/gif";
        if (lower.endsWith(".svg"))  return "image/svg+xml";
        if (lower.endsWith(".zip"))  return "application/zip";
        if (lower.endsWith(".xml"))  return "application/xml; charset=utf-8";
        return "application/octet-stream";
    }
}
