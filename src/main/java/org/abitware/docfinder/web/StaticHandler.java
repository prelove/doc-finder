package org.abitware.docfinder.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Serves static assets from the classpath {@code /web/} directory.
 *
 * <ul>
 *   <li>{@code /} and {@code /index.html} → {@code /web/index.html}</li>
 *   <li>{@code /preview} → {@code /web/preview.html} (file preview without search)</li>
 *   <li>{@code /share/*} → {@code /web/share.html} (token read by JS from URL)</li>
 *   <li>{@code /web/<file>} → {@code /web/<file>} directly from classpath</li>
 *   <li>anything else → 404</li>
 * </ul>
 */
class StaticHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();

        String resource;
        if ("/".equals(path) || "/index.html".equals(path)) {
            resource = "/web/index.html";
        } else if ("/preview".equals(path) || "/preview.html".equals(path)) {
            resource = "/web/preview.html";
        } else if (path.startsWith("/share/")) {
            resource = "/web/share.html";
        } else if (path.startsWith("/web/")) {
            // Direct classpath asset (JS, CSS, …)
            resource = path;
        } else {
            byte[] body = "Not Found".getBytes("UTF-8");
            exchange.sendResponseHeaders(404, body.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(body); }
            return;
        }

        InputStream in = StaticHandler.class.getResourceAsStream(resource);
        if (in == null) {
            byte[] body = ("Not bundled: " + resource).getBytes("UTF-8");
            exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(404, body.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(body); }
            return;
        }

        byte[] content = readAll(in);
        exchange.getResponseHeaders().add("Content-Type", mimeFor(resource));
        exchange.getResponseHeaders().add("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, content.length);
        try (OutputStream os = exchange.getResponseBody()) { os.write(content); }
    }

    private static String mimeFor(String resource) {
        if (resource.endsWith(".html")) return "text/html; charset=utf-8";
        if (resource.endsWith(".js"))   return "application/javascript; charset=utf-8";
        if (resource.endsWith(".css"))  return "text/css; charset=utf-8";
        if (resource.endsWith(".png"))  return "image/png";
        if (resource.endsWith(".svg"))  return "image/svg+xml";
        return "application/octet-stream";
    }

    static byte[] readAll(InputStream in) throws IOException {
        byte[] buf = new byte[8192];
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        int n;
        while ((n = in.read(buf)) != -1) baos.write(buf, 0, n);
        return baos.toByteArray();
    }
}
