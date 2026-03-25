package org.abitware.docfinder.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.apache.tika.parser.txt.CharsetDetector;
import org.apache.tika.parser.txt.CharsetMatch;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashSet;
import java.util.Map;

/**
 * Serves text file content at {@code GET /api/text?path=<encoded-abs-path>}
 * with reliable encoding detection via ICU4J (Tika {@link CharsetDetector}).
 *
 * <p>The handler reads the raw bytes, detects the encoding using statistical
 * analysis (BOM, byte-pattern heuristics), converts the content to UTF-8,
 * and serves it as {@code text/plain; charset=utf-8}.
 *
 * <p>An optional query parameter {@code limit} caps the number of bytes read
 * (default 512 KB). The response header {@code X-Detected-Charset} reports the
 * detected source encoding. {@code X-Truncated} is {@code true} when the file
 * was larger than the limit.
 */
class TextServeHandler implements HttpHandler {

    private static final int DEFAULT_LIMIT = 512 * 1024; // 512 KB

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

        int limit = DEFAULT_LIMIT;
        String limitStr = params.get("limit");
        if (limitStr != null) {
            try {
                limit = Math.max(1024, Math.min(Integer.parseInt(limitStr), 5 * 1024 * 1024));
            } catch (NumberFormatException ignored) {
            }
        }

        long fileSize = Files.size(filePath);
        boolean truncated = fileSize > limit;
        int readLen = (int) Math.min(fileSize, limit);

        byte[] buf = new byte[readLen];
        int totalRead = 0;
        try (InputStream is = Files.newInputStream(filePath, StandardOpenOption.READ)) {
            while (totalRead < readLen) {
                int n = is.read(buf, totalRead, readLen - totalRead);
                if (n < 0) break;
                totalRead += n;
            }
        } catch (IOException e) {
            SearchHandler.sendJson(exchange, 500,
                    "{\"error\":\"Cannot read file: " + SearchHandler.escapeJson(e.getMessage()) + "\"}");
            return;
        }

        if (totalRead <= 0) {
            exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
            exchange.getResponseHeaders().add("X-Detected-Charset", "utf-8");
            exchange.getResponseHeaders().add("X-Truncated", "false");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().close();
            return;
        }

        // Detect charset using Tika's ICU4J-based CharsetDetector
        Charset detected = detectCharset(buf, totalRead);
        String text = new String(buf, 0, totalRead, detected);

        // Strip BOM if present
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }

        byte[] utf8Bytes = text.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
        exchange.getResponseHeaders().add("X-Detected-Charset", detected.name());
        exchange.getResponseHeaders().add("X-Truncated", String.valueOf(truncated));
        exchange.getResponseHeaders().add("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, utf8Bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(utf8Bytes);
        }
    }

    /**
     * Detects the charset of the given byte buffer using BOM detection first,
     * then ICU4J statistical analysis, with sensible fallbacks.
     */
    static Charset detectCharset(byte[] buf, int len) {
        // 1. BOM detection (highest priority)
        if (len >= 3 && (buf[0] & 0xFF) == 0xEF && (buf[1] & 0xFF) == 0xBB && (buf[2] & 0xFF) == 0xBF) {
            return StandardCharsets.UTF_8;
        }
        if (len >= 2 && (buf[0] & 0xFF) == 0xFF && (buf[1] & 0xFF) == 0xFE) {
            return StandardCharsets.UTF_16LE;
        }
        if (len >= 2 && (buf[0] & 0xFF) == 0xFE && (buf[1] & 0xFF) == 0xFF) {
            return StandardCharsets.UTF_16BE;
        }

        // 2. ICU4J / Tika statistical detection
        try {
            CharsetDetector detector = new CharsetDetector();
            detector.setText(new ByteArrayInputStream(buf, 0, len));
            CharsetMatch[] matches = detector.detectAll();
            if (matches != null && matches.length > 0) {
                // Collect candidates above confidence threshold
                LinkedHashSet<String> candidates = new LinkedHashSet<>();
                for (CharsetMatch match : matches) {
                    if (match == null) continue;
                    // Accept matches with confidence >= 20 (ICU4J's scale is 0-100)
                    if (match.getConfidence() >= 20) {
                        candidates.add(match.getName());
                    }
                }

                // Try the best match first; if it decodes cleanly, use it
                for (String csName : candidates) {
                    try {
                        Charset cs = Charset.forName(csName);
                        // Quick validation: decode and re-encode to check for issues
                        String sample = new String(buf, 0, Math.min(len, 8192), cs);
                        if (!sample.contains("\uFFFD") || csName.equalsIgnoreCase("UTF-8")) {
                            return cs;
                        }
                    } catch (Exception ignored) {
                    }
                }

                // If we have any match at all, use the top one
                if (!candidates.isEmpty()) {
                    try {
                        return Charset.forName(candidates.iterator().next());
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception ignored) {
        }

        // 3. Fallback: try UTF-8 validity check
        if (isValidUtf8(buf, len)) {
            return StandardCharsets.UTF_8;
        }

        // 4. Final fallback
        return StandardCharsets.ISO_8859_1;
    }

    /** Quick UTF-8 validity check by scanning byte sequences. */
    private static boolean isValidUtf8(byte[] buf, int len) {
        int i = 0;
        while (i < len) {
            int b = buf[i] & 0xFF;
            int seqLen;
            if (b <= 0x7F) {
                seqLen = 1;
            } else if (b >= 0xC2 && b <= 0xDF) {
                seqLen = 2;
            } else if (b >= 0xE0 && b <= 0xEF) {
                seqLen = 3;
            } else if (b >= 0xF0 && b <= 0xF4) {
                seqLen = 4;
            } else {
                return false;
            }
            if (i + seqLen > len) return false;
            for (int j = 1; j < seqLen; j++) {
                if ((buf[i + j] & 0xC0) != 0x80) return false;
            }
            i += seqLen;
        }
        return true;
    }
}
