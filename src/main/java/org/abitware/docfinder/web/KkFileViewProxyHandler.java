package org.abitware.docfinder.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Proxies requests to the kkFileView server.
 *
 * This handler forwards preview requests from DocFinder's web interface
 * to the embedded kkFileView server, passing through headers and response data.
 */
public class KkFileViewProxyHandler implements HttpHandler {
    private static final Logger log = LoggerFactory.getLogger(KkFileViewProxyHandler.class);

    private final Supplier<KkFileViewServer> serverSupplier;

    /**
     * Create a new proxy handler.
     * @param serverSupplier Supplier that provides the KkFileViewServer instance
     */
    public KkFileViewProxyHandler(Supplier<KkFileViewServer> serverSupplier) {
        this.serverSupplier = serverSupplier;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        KkFileViewServer server = serverSupplier.get();

        if (server == null || !server.isRunning()) {
            sendError(exchange, 503, "kkFileView server is not running");
            return;
        }

        try {
            String path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getRawQuery();

            // Remove /api/kkfileview prefix and forward to kkFileView server
            String kkFileViewPath = path.replaceFirst("^/api/kkfileview", "");
            if (kkFileViewPath.isEmpty()) {
                kkFileViewPath = "/";
            }

            String targetUrl = server.getBaseUrl() + kkFileViewPath;
            if (query != null && !query.isEmpty()) {
                targetUrl += "?" + query;
            }

            log.debug("Proxying request to kkFileView: {} -> {}", path, targetUrl);

            // Create connection to kkFileView server
            URL url = new URL(targetUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod(exchange.getRequestMethod());
            conn.setDoInput(true);
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(300000); // 5 minutes for large files

            // Copy request headers (except Host)
            for (Map.Entry<String, List<String>> header : exchange.getRequestHeaders().entrySet()) {
                if (!"Host".equalsIgnoreCase(header.getKey())) {
                    for (String value : header.getValue()) {
                        conn.addRequestProperty(header.getKey(), value);
                    }
                }
            }

            // Copy request body for POST/PUT requests
            if ("POST".equals(exchange.getRequestMethod()) || "PUT".equals(exchange.getRequestMethod())) {
                conn.setDoOutput(true);
                try (InputStream in = exchange.getRequestBody();
                     OutputStream out = conn.getOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = in.read(buffer)) != -1) {
                        out.write(buffer, 0, bytesRead);
                    }
                }
            }

            // Get response from kkFileView
            int responseCode = conn.getResponseCode();

            // Copy response headers
            for (Map.Entry<String, List<String>> header : conn.getHeaderFields().entrySet()) {
                if (header.getKey() != null) {
                    for (String value : header.getValue()) {
                        exchange.getResponseHeaders().add(header.getKey(), value);
                    }
                }
            }

            // Send response
            exchange.sendResponseHeaders(responseCode, 0);

            // Copy response body
            try (InputStream in = (responseCode >= 200 && responseCode < 300)
                    ? conn.getInputStream()
                    : conn.getErrorStream();
                 OutputStream out = exchange.getResponseBody()) {
                if (in != null) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = in.read(buffer)) != -1) {
                        out.write(buffer, 0, bytesRead);
                    }
                }
            }

        } catch (Exception e) {
            log.error("Error proxying request to kkFileView", e);
            if (!exchange.getResponseHeaders().containsKey("Content-Type")) {
                sendError(exchange, 500, "Error proxying to kkFileView: " + e.getMessage());
            }
        } finally {
            exchange.close();
        }
    }

    private void sendError(HttpExchange exchange, int code, String message) throws IOException {
        byte[] response = message.getBytes("UTF-8");
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
        exchange.sendResponseHeaders(code, response.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(response);
        }
    }
}
