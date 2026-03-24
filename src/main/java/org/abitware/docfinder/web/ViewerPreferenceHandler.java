package org.abitware.docfinder.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.abitware.docfinder.index.ConfigManager;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * HTTP handler for getting and setting viewer preferences.
 * GET /api/viewer - returns current preferred viewer
 * POST /api/viewer - sets preferred viewer (expects JSON: {"viewer":"kkfileview"} or {"viewer":"jitviewer"})
 */
public class ViewerPreferenceHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();

        try {
            if ("GET".equals(method)) {
                handleGet(exchange);
            } else if ("POST".equals(method)) {
                handlePost(exchange);
            } else {
                sendError(exchange, 405, "Method not allowed");
            }
        } catch (Exception e) {
            sendError(exchange, 500, "Server error: " + e.getMessage());
        }
    }

    private void handleGet(HttpExchange exchange) throws IOException {
        ConfigManager cm = new ConfigManager();
        String viewer = cm.getPreferredViewer();

        String json = "{\"viewer\":\"" + viewer + "\"}";
        byte[] response = json.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(200, response.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(response);
        }
    }

    private void handlePost(HttpExchange exchange) throws IOException {
        // Read the request body
        byte[] bodyBytes = new byte[1024];
        int length = exchange.getRequestBody().read(bodyBytes);
        String body = new String(bodyBytes, 0, length, StandardCharsets.UTF_8);

        // Parse simple JSON {"viewer":"..."}
        String viewer = "jitviewer"; // default
        if (body.contains("\"viewer\"")) {
            int start = body.indexOf("\"viewer\"");
            int colon = body.indexOf(":", start);
            int valueStart = body.indexOf("\"", colon) + 1;
            int valueEnd = body.indexOf("\"", valueStart);
            if (valueStart > 0 && valueEnd > valueStart) {
                viewer = body.substring(valueStart, valueEnd);
            }
        }

        // Validate viewer value
        if (!"kkfileview".equals(viewer) && !"jitviewer".equals(viewer)) {
            sendError(exchange, 400, "Invalid viewer: must be 'kkfileview' or 'jitviewer'");
            return;
        }

        // Save preference
        ConfigManager cm = new ConfigManager();
        cm.setPreferredViewer(viewer);

        String json = "{\"viewer\":\"" + viewer + "\",\"status\":\"ok\"}";
        byte[] response = json.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(200, response.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(response);
        }
    }

    private void sendError(HttpExchange exchange, int code, String message) throws IOException {
        String json = "{\"error\":\"" + message.replace("\"", "\\\"") + "\"}";
        byte[] response = json.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(code, response.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(response);
        }
    }
}
