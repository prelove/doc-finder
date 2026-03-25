package org.abitware.docfinder.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.*;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.util.Map;

/**
 * Serves raw file bytes at {@code GET /api/file?path=<encoded-abs-path>}.
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
     * Sends file bytes with an appropriate Content-Type and optional
     * Content-Disposition header.
     */
    static void serveFile(HttpExchange exchange, Path filePath, String downloadName) throws IOException {
        String mime = guessMime(filePath.getFileName().toString());
        long size = Files.size(filePath);

        // For text MIME types without explicit charset, detect encoding and append it
        if (mime.startsWith("text/") && !mime.contains("charset")) {
            try {
                int sampleSize = (int) Math.min(size, 64 * 1024);
                byte[] sample = new byte[sampleSize];
                int read = 0;
                try (InputStream is = Files.newInputStream(filePath)) {
                    while (read < sampleSize) {
                        int n = is.read(sample, read, sampleSize - read);
                        if (n < 0) break;
                        read += n;
                    }
                }
                if (read > 0) {
                    Charset detected = TextServeHandler.detectCharset(sample, read);
                    mime = mime + "; charset=" + detected.name().toLowerCase();
                }
            } catch (Exception e) {
                // detection failed; serve without charset (browser will guess)
            }
        }

        exchange.getResponseHeaders().add("Content-Type", mime);
        if (downloadName != null) {
            exchange.getResponseHeaders().add("Content-Disposition",
                    "attachment; filename=\"" + downloadName.replace("\"", "'") + "\"");
        }
        exchange.getResponseHeaders().add("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, size);
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
        String lower = filename.toLowerCase();
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
