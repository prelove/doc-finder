package org.abitware.docfinder.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Handles all {@code /api/share/*} endpoints:
 *
 * <pre>
 *   POST /api/share/create   – create a share
 *   GET  /api/share/list     – list all shares (admin)
 *   POST /api/share/revoke   – revoke a share (param: token)
 *   GET  /api/share/{token}/info       – check share (no file; reveals passwordRequired)
 *   GET  /api/share/{token}/file       – download/stream file (password in query)
 * </pre>
 *
 * <p>Create body (application/x-www-form-urlencoded or query-string style):
 * {@code path=<encoded>&expiryHours=24&password=secret&maxDownloads=10}
 *
 * <p>File endpoint sets Content-Disposition so browsers download rather than
 * try to render unknown formats; the share page uses an object/iframe for viewing.
 */
class ShareHandler implements HttpHandler {

    private final ShareManager manager;
    /** Base URL (scheme + host + port) injected at construction so share links are absolute. */
    private volatile String baseUrl;

    ShareHandler(ShareManager manager, String baseUrl) {
        this.manager = manager;
        this.baseUrl = baseUrl == null ? "" : baseUrl;
    }

    void setBaseUrl(String url) {
        this.baseUrl = url == null ? "" : url;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        // strip leading /api/share
        String sub = path.startsWith("/api/share") ? path.substring("/api/share".length()) : path;
        if (sub.startsWith("/")) sub = sub.substring(1);

        String method = exchange.getRequestMethod().toUpperCase();

        // POST /api/share/create
        if ("create".equals(sub) && "POST".equals(method)) {
            handleCreate(exchange);
            return;
        }
        // GET /api/share/list
        if ("list".equals(sub) && "GET".equals(method)) {
            handleList(exchange);
            return;
        }
        // POST /api/share/revoke
        if ("revoke".equals(sub) && "POST".equals(method)) {
            handleRevoke(exchange);
            return;
        }

        // /api/share/{token}/info  or  /api/share/{token}/file
        String[] parts = sub.split("/", 2);
        if (parts.length == 2) {
            String token = parts[0];
            String action = parts[1];
            if ("info".equals(action) && "GET".equals(method)) {
                handleInfo(exchange, token);
                return;
            }
            if ("file".equals(action) && ("GET".equals(method) || "HEAD".equals(method))) {
                handleFile(exchange, token);
                return;
            }
        }

        SearchHandler.sendJson(exchange, 404, "{\"error\":\"Not found\"}");
    }

    // -------------------------------------------------------------------------

    private void handleCreate(HttpExchange exchange) throws IOException {
        Map<String, String> params = readParams(exchange);

        String filePath = params.get("path");
        if (filePath == null || filePath.trim().isEmpty() || filePath.contains("..")) {
            SearchHandler.sendJson(exchange, 400, "{\"error\":\"Invalid or missing path\"}");
            return;
        }
        Path p;
        try { p = Paths.get(filePath).toAbsolutePath().normalize(); }
        catch (Exception e) { SearchHandler.sendJson(exchange, 400, "{\"error\":\"Invalid path\"}"); return; }

        if (!Files.exists(p) || Files.isDirectory(p)) {
            SearchHandler.sendJson(exchange, 404, "{\"error\":\"File not found\"}");
            return;
        }

        int expiryHours = parseIntOrDefault(params.get("expiryHours"), 24);
        String password = params.getOrDefault("password", "").trim();
        int maxDownloads = parseIntOrDefault(params.get("maxDownloads"), 0);

        ShareManager.ShareEntry entry = manager.createShare(
                p.toString(), expiryHours, password.isEmpty() ? null : password, maxDownloads);

        String url = baseUrl + "/share/" + entry.token;
        String json = "{"
                + "\"token\":\"" + entry.token + "\","
                + "\"url\":\"" + SearchHandler.escapeJson(url) + "\","
                + "\"expiry\":" + entry.expiresAt + ","
                + "\"passwordRequired\":" + (entry.passwordHash != null)
                + "}";
        SearchHandler.sendJson(exchange, 200, json);
    }

    private void handleList(HttpExchange exchange) throws IOException {
        manager.cleanExpired();
        List<ShareManager.ShareEntry> list = manager.listShares();
        StringBuilder sb = new StringBuilder("{\"shares\":[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(',');
            ShareManager.ShareEntry e = list.get(i);
            sb.append('{');
            sb.append("\"token\":\"").append(e.token).append("\",");
            sb.append("\"path\":\"").append(SearchHandler.escapeJson(e.filePath)).append("\",");
            sb.append("\"expiry\":").append(e.expiresAt).append(',');
            sb.append("\"expired\":").append(manager.isExpired(e)).append(',');
            sb.append("\"passwordRequired\":").append(e.passwordHash != null).append(',');
            sb.append("\"downloads\":").append(e.downloadCount).append(',');
            sb.append("\"maxDownloads\":").append(e.maxDownloads).append(',');
            sb.append("\"created\":").append(e.createdAt);
            sb.append('}');
        }
        sb.append("]}");
        SearchHandler.sendJson(exchange, 200, sb.toString());
    }

    private void handleRevoke(HttpExchange exchange) throws IOException {
        Map<String, String> params = readParams(exchange);
        String token = params.get("token");
        if (token == null || token.isEmpty()) {
            SearchHandler.sendJson(exchange, 400, "{\"error\":\"Missing token\"}");
            return;
        }
        manager.revokeShare(token);
        SearchHandler.sendJson(exchange, 200, "{\"ok\":true}");
    }

    private void handleInfo(HttpExchange exchange, String token) throws IOException {
        ShareManager.ShareEntry e = manager.getShare(token);
        if (e == null) {
            SearchHandler.sendJson(exchange, 404, "{\"error\":\"Share not found\"}");
            return;
        }
        if (manager.isExpired(e)) {
            SearchHandler.sendJson(exchange, 410, "{\"error\":\"Share has expired\"}");
            return;
        }
        Path fp = Paths.get(e.filePath);
        String name = fp.getFileName() != null ? fp.getFileName().toString() : e.filePath;
        String json = "{"
                + "\"token\":\"" + token + "\","
                + "\"name\":\"" + SearchHandler.escapeJson(name) + "\","
                + "\"passwordRequired\":" + (e.passwordHash != null) + ","
                + "\"expiry\":" + e.expiresAt + ","
                + "\"downloads\":" + e.downloadCount + ","
                + "\"maxDownloads\":" + e.maxDownloads
                + "}";
        SearchHandler.sendJson(exchange, 200, json);
    }

    private void handleFile(HttpExchange exchange, String token) throws IOException {
        Map<String, String> params = SearchHandler.parseQuery(exchange.getRequestURI());
        String password = params.getOrDefault("password", "");

        ShareManager.ShareEntry e = manager.getShare(token);
        if (e == null) {
            SearchHandler.sendJson(exchange, 404, "{\"error\":\"Share not found\"}");
            return;
        }
        if (manager.isExpired(e)) {
            SearchHandler.sendJson(exchange, 410, "{\"error\":\"Share has expired\"}");
            return;
        }
        if (e.maxDownloads > 0 && e.downloadCount >= e.maxDownloads) {
            SearchHandler.sendJson(exchange, 403, "{\"error\":\"Download limit reached\"}");
            return;
        }
        if (e.passwordHash != null) {
            if (password.isEmpty() || !e.passwordHash.equals(ShareManager.sha256(password))) {
                SearchHandler.sendJson(exchange, 401, "{\"error\":\"Invalid or missing password\"}");
                return;
            }
        }

        Path filePath = Paths.get(e.filePath).toAbsolutePath().normalize();
        if (!Files.exists(filePath) || Files.isDirectory(filePath)) {
            SearchHandler.sendJson(exchange, 404, "{\"error\":\"File no longer available\"}");
            return;
        }

        // HEAD: validate credentials and return headers only (no body, no download count increment)
        if ("HEAD".equals(exchange.getRequestMethod().toUpperCase())) {
            exchange.getResponseHeaders().add("Content-Type", FileServeHandler.guessMime(filePath.getFileName().toString()));
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            exchange.sendResponseHeaders(200, -1);
            return;
        }

        manager.recordDownload(token);

        String filename = filePath.getFileName() != null ? filePath.getFileName().toString() : "file";
        // Serve without forced download so the viewer can embed it
        exchange.getResponseHeaders().add("Content-Disposition",
                "inline; filename=\"" + filename.replace("\"", "'") + "\"");
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        FileServeHandler.serveFile(exchange, filePath, null);
    }

    // -------------------------------------------------------------------------

    /** Reads params from query string (GET) or application/x-www-form-urlencoded body (POST). */
    private static Map<String, String> readParams(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod().toUpperCase();
        if ("POST".equals(method)) {
            try (InputStream is = exchange.getRequestBody()) {
                byte[] buf = new byte[4096];
                int n = is.read(buf);
                String body = n > 0 ? new String(buf, 0, n, StandardCharsets.UTF_8) : "";
                return parseFormBody(body);
            }
        }
        return SearchHandler.parseQuery(exchange.getRequestURI());
    }

    private static Map<String, String> parseFormBody(String body) {
        Map<String, String> map = new LinkedHashMap<>();
        if (body == null || body.isEmpty()) return map;
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                map.put(decode(pair.substring(0, eq)), decode(pair.substring(eq + 1)));
            }
        }
        return map;
    }

    private static String decode(String s) {
        try { return java.net.URLDecoder.decode(s, "UTF-8"); }
        catch (Exception e) { return s; }
    }

    private static int parseIntOrDefault(String s, int def) {
        if (s == null) return def;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return def; }
    }
}
