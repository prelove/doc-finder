package org.abitware.docfinder.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.abitware.docfinder.search.*;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 处理 GET /api/search 请求，返回 JSON 格式的搜索结果。
 * 查询参数:
 *   q      – 搜索关键词（必填）
 *   scope  – ALL | NAME | CONTENT | FOLDER（默认 ALL）
 *   mode   – FUZZY | EXACT（默认 FUZZY）
 *   ext    – 扩展名过滤（可选，多个用逗号分隔，如 pdf,docx）
 *   limit  – 最大返回条数（默认 50，上限 200）
 */
class SearchHandler implements HttpHandler {

    private final Supplier<SearchService> serviceSupplier;

    SearchHandler(Supplier<SearchService> serviceSupplier) {
        this.serviceSupplier = serviceSupplier;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendText(exchange, 405, "Method Not Allowed");
            return;
        }

        Map<String, String> params = parseQuery(exchange.getRequestURI());
        String q = params.getOrDefault("q", "").trim();
        if (q.isEmpty()) {
            sendJson(exchange, 200, "{\"results\":[],\"count\":0}");
            return;
        }

        SearchService svc = serviceSupplier.get();
        if (svc == null) {
            sendJson(exchange, 503, "{\"error\":\"Search index is initializing, please retry in a moment\"}");
            return;
        }

        SearchScope scope;
        try { scope = SearchScope.valueOf(params.getOrDefault("scope", "ALL").toUpperCase()); }
        catch (IllegalArgumentException e) { scope = SearchScope.ALL; }

        MatchMode mode;
        try { mode = MatchMode.valueOf(params.getOrDefault("mode", "FUZZY").toUpperCase()); }
        catch (IllegalArgumentException e) { mode = MatchMode.FUZZY; }

        int limit = Math.min(200, Math.max(1, parseIntOrDefault(params.get("limit"), 50)));

        // 构建可选 ext 过滤器
        FilterState filter = null;
        String extParam = params.get("ext");
        if (extParam != null && !extParam.trim().isEmpty()) {
            filter = new FilterState();
            filter.exts.clear();
            for (String e : extParam.split(",")) {
                String ext = e.trim().toLowerCase();
                if (!ext.isEmpty()) filter.exts.add(ext);
            }
        }

        SearchRequest req = new SearchRequest(q, limit, filter, scope, mode);
        List<SearchResult> results;
        try {
            results = svc.search(req);
        } catch (Exception e) {
            sendJson(exchange, 500, "{\"error\":\"Search failed: " + escapeJson(e.getMessage()) + "\"}");
            return;
        }

        sendJson(exchange, 200, toJson(results));
    }

    // --- JSON 序列化（不依赖外部库）---

    private static String toJson(List<SearchResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"count\":").append(results.size()).append(",\"results\":[");
        for (int i = 0; i < results.size(); i++) {
            if (i > 0) sb.append(',');
            SearchResult r = results.get(i);
            sb.append('{');
            sb.append("\"name\":\"").append(escapeJson(r.name)).append("\",");
            sb.append("\"path\":\"").append(escapeJson(r.path)).append("\",");
            sb.append("\"score\":").append(String.format("%.4f", r.score)).append(',');
            sb.append("\"ctime\":").append(r.ctime).append(',');
            sb.append("\"atime\":").append(r.atime).append(',');
            sb.append("\"size\":").append(r.sizeBytes).append(',');
            sb.append("\"match\":\"").append(escapeJson(r.match)).append("\",");
            sb.append("\"directory\":").append(r.directory);
            sb.append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    // --- HTTP helpers ---

    static void sendJson(HttpExchange ex, int code, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(body); }
    }

    static void sendText(HttpExchange ex, int code, String text) throws IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(body); }
    }

    /** 解析 URI 查询参数（简单实现，不依赖外部库）*/
    static Map<String, String> parseQuery(URI uri) {
        Map<String, String> params = new LinkedHashMap<>();
        String raw = uri.getRawQuery();
        if (raw == null || raw.isEmpty()) return params;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                String key = decode(pair.substring(0, eq));
                String val = decode(pair.substring(eq + 1));
                params.put(key, val);
            }
        }
        return params;
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
