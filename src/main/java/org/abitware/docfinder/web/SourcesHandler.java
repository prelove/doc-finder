package org.abitware.docfinder.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.abitware.docfinder.search.SearchErrorMessages;
import org.abitware.docfinder.search.SearchException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 处理 GET /api/sources，返回已配置的索引来源列表。
 *
 * <p>读取与检索一致的严格视图（SourceManager.loadSearchRoots）：无效行被跳过，
 * 全部无效或读取失败时返回 503 + {"error","code":"SOURCE_CONFIG_UNAVAILABLE"}。
 * 响应为 {"sources":["<abs path>", ...]}，仅含路径，供前端构建目录范围下拉。
 */
class SourcesHandler implements HttpHandler {

    private static final Logger log = Logger.getLogger(SourcesHandler.class.getName());

    /** Loads the strict search-time source roots; may throw IOException. */
    interface RootsLoader {
        List<Path> load() throws IOException;
    }

    private final RootsLoader rootsLoader;

    SourcesHandler(RootsLoader rootsLoader) {
        this.rootsLoader = rootsLoader;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            SearchHandler.sendText(exchange, 405, "Method Not Allowed");
            return;
        }

        List<Path> roots;
        try {
            roots = rootsLoader.load();
        } catch (IOException e) {
            log.log(Level.WARNING, "Failed to load source roots", e);
            SearchException.Code code = SearchException.Code.SOURCE_CONFIG_UNAVAILABLE;
            String body = "{\"error\":\"" + SearchHandler.escapeJson(SearchErrorMessages.forCode(code))
                    + "\",\"code\":\"" + code.name() + "\"}";
            SearchHandler.sendJson(exchange, 503, body);
            return;
        }

        StringBuilder sb = new StringBuilder("{\"sources\":[");
        for (int i = 0; i < roots.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(SearchHandler.escapeJson(roots.get(i).toString())).append('"');
        }
        sb.append("]}");
        SearchHandler.sendJson(exchange, 200, sb.toString());
    }
}
