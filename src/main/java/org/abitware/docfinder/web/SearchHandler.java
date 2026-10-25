package org.abitware.docfinder.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.abitware.docfinder.search.*;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 处理 GET /api/search 请求，返回 JSON 格式的搜索结果。
 * 查询参数:
 *   q      – 搜索关键词（必填）
 *   scope  – ALL | NAME | CONTENT | FOLDER（默认 ALL）
 *   mode   – FUZZY | EXACT（默认 FUZZY）
 *   ext    – 扩展名过滤（可选，多个用逗号分隔，如 pdf,docx）
 *   limit  – 最大返回条数（默认 50，上限 200）
 *   directory – 可选服务器目录范围（含全部子目录，须位于已配置来源内）
 *
 * 错误统一为 {"error": <固定文案>, "code": <SearchException.Code>}，
 * HTTP 状态按 design D3 映射；不外泄原始异常消息/堆栈。
 */
class SearchHandler implements HttpHandler {

    private static final Logger log = Logger.getLogger(SearchHandler.class.getName());

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
            sendError(exchange, SearchException.Code.INDEX_UNAVAILABLE);
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
                String ext = e.trim().toLowerCase(Locale.ROOT);
                if (!ext.isEmpty()) filter.exts.add(ext);
            }
        }

        String directory = params.get("directory");
        if (directory != null && directory.trim().isEmpty()) {
            directory = null;
        }

        // Each web request is an independent session (null tag): two
        // clients — or two tabs — behind the same IP must never cancel
        // each other's in-flight searches.
        SearchRequest req = new SearchRequest(q, limit, filter, scope, mode, directory);
        SearchOutcome outcome;
        try {
            outcome = svc.searchDetailed(req);
        } catch (SearchException e) {
            log.log(Level.WARNING, "Search failed (code=" + e.getCode() + "): " + e.getMessage(), e);
            sendError(exchange, e.getCode());
            return;
        } catch (Exception e) {
            log.log(Level.WARNING, "Search failed unexpectedly", e);
            sendError(exchange, SearchException.Code.SEARCH_FAILED);
            return;
        }

        sendJson(exchange, 200, toJson(outcome));
    }

    private static int statusFor(SearchException.Code code) {
        if (code == null) return 500;
        switch (code) {
            case INVALID_DIRECTORY:         return 400;
            case DIRECTORY_NOT_INDEXED:     return 422;
            case SOURCE_CONFIG_UNAVAILABLE: return 503;
            case INDEX_REBUILD_REQUIRED:    return 409;
            case SCOPE_CHECK_FAILED:        return 503;
            case SEARCH_FAILED:             return 500;
            case INVALID_QUERY:             return 400;
            case INDEX_UNAVAILABLE:         return 503;
            default:                        return 500;
        }
    }

    private static void sendError(HttpExchange exchange, SearchException.Code code) throws IOException {
        SearchException.Code effective = code == null ? SearchException.Code.SEARCH_FAILED : code;
        String body = "{\"error\":\"" + escapeJson(SearchErrorMessages.forCode(effective))
                + "\",\"code\":\"" + effective.name() + "\"}";
        sendJson(exchange, statusFor(effective), body);
    }

    // --- JSON 序列化（不依赖外部库）---

    private static String toJson(SearchOutcome outcome) {
        List<SearchResult> results = outcome.results;
        StringBuilder sb = new StringBuilder();
        sb.append("{\"count\":").append(results.size());
        // Tri-state truncation (D5): hasMore and truncated always agree, and
        // UNKNOWN omits both fields rather than guessing false.
        if (outcome.hasMore != TriState.UNKNOWN) {
            boolean more = outcome.hasMore == TriState.TRUE;
            sb.append(",\"hasMore\":").append(more).append(",\"truncated\":").append(more);
        }
        // Entries that could not be existence-checked (offline source,
        // permission/timeout indeterminate) are kept but explicitly flagged.
        if (outcome.hasUnverified) {
            sb.append(",\"unverified\":true");
        }
        sb.append(",\"results\":[");
        for (int i = 0; i < results.size(); i++) {
            if (i > 0) sb.append(',');
            SearchResult r = results.get(i);
            sb.append('{');
            sb.append("\"name\":\"").append(escapeJson(r.name)).append("\",");
            sb.append("\"path\":\"").append(escapeJson(r.path)).append("\",");
            sb.append("\"score\":").append(String.format(Locale.ROOT, "%.4f", r.score)).append(',');
            sb.append("\"ctime\":").append(r.ctime).append(',');
            sb.append("\"atime\":").append(r.atime).append(',');
            sb.append("\"size\":").append(r.sizeBytes).append(',');
            sb.append("\"match\":\"").append(escapeJson(r.match)).append("\",");
            sb.append("\"directory\":").append(r.directory).append(',');
            String parent = parentOf(r.path);
            if (parent == null) {
                sb.append("\"parentDirectory\":null");
            } else {
                sb.append("\"parentDirectory\":\"").append(escapeJson(parent)).append("\"");
            }
            sb.append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    /** Parent directory of a result path on the server host; null if none/unparseable. No disk I/O. */
    static String parentOf(String path) {
        if (path == null) return null;
        try {
            Path parent = Paths.get(path).getParent();
            return parent == null ? null : parent.toString();
        } catch (Exception e) {
            return null;
        }
    }

    static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"':  sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
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
