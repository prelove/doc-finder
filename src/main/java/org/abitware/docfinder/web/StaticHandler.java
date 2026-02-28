package org.abitware.docfinder.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 提供静态文件服务：将 / 路由到内嵌的 HTML 搜索界面（web/index.html）。
 */
class StaticHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        // 仅支持根路径，其他全部返回 404
        if (!"/".equals(path) && !"/index.html".equals(path)) {
            byte[] body = "Not Found".getBytes("UTF-8");
            exchange.sendResponseHeaders(404, body.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(body); }
            return;
        }

        InputStream in = StaticHandler.class.getResourceAsStream("/web/index.html");
        if (in == null) {
            byte[] body = "UI not bundled".getBytes("UTF-8");
            exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(500, body.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(body); }
            return;
        }

        byte[] content = readAll(in);
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, content.length);
        try (OutputStream os = exchange.getResponseBody()) { os.write(content); }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        byte[] buf = new byte[4096];
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        int n;
        while ((n = in.read(buf)) != -1) baos.write(buf, 0, n);
        return baos.toByteArray();
    }
}
