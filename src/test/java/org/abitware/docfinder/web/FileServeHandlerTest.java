package org.abitware.docfinder.web;

import com.sun.net.httpserver.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.net.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

import static org.junit.jupiter.api.Assertions.*;

class FileServeHandlerTest {
    @TempDir Path directory;

    @Test
    void shouldTranscodeKoreanTextToUtf8() throws Exception {
        assertUtf8("한글 문서의 내용을 검색하고 문자 인코딩을 확인합니다. ".repeat(20), "EUC-KR", "korean.txt");
    }

    @Test
    void shouldTranscodeChineseTextToUtf8() throws Exception {
        assertUtf8("中文文件内容编码检测，搜索文档应该保持原始内容。 ".repeat(20), "GBK", "chinese.txt");
    }

    @Test
    void shouldTranscodeJapaneseTextToUtf8() throws Exception {
        assertUtf8("日本語の文書を検索して内容を確認します。 ".repeat(20), "Shift_JIS", "japanese.txt");
    }

    @Test
    void shouldPreserveCp932Extensions() throws Exception {
        assertUtf8("会議資料①～③…㈱ 検索文書の内容を確認します。 ".repeat(20), "windows-31j", "cp932.txt");
    }

    @Test
    void shouldTranscodeTextMimeWithExistingCharset() throws Exception {
        assertUtf8("中文文件内容编码检测，搜索文档应该保持原始内容。 ".repeat(20), "GBK", "chinese.md");
    }

    @Test
    void shouldStripBomAndServeEmptyText() throws Exception {
        assertUtf8("", "UTF-8", "empty.txt");
        Path file = Files.write(directory.resolve("bom.txt"), "\uFEFF中文内容".getBytes(StandardCharsets.UTF_8));
        RecordingExchange exchange = new RecordingExchange();
        FileServeHandler.serveFile(exchange, file, null);
        assertEquals("中文内容", exchange.body.toString(StandardCharsets.UTF_8));
    }

    @Test
    void shouldPreserveBinaryPayloadAndDownloadName() throws Exception {
        byte[] bytes = {0, (byte) 0xFF, 1, 2};
        Path file = Files.write(directory.resolve("test.pdf"), bytes);
        RecordingExchange exchange = new RecordingExchange();
        FileServeHandler.serveFile(exchange, file, "report.pdf");
        assertEquals("application/pdf", exchange.headers.getFirst("Content-Type"));
        assertEquals("attachment; filename=\"report.pdf\"", exchange.headers.getFirst("Content-Disposition"));
        assertArrayEquals(bytes, exchange.body.toByteArray());
        assertEquals(bytes.length, exchange.length);
    }

    private void assertUtf8(String text, String charset, String name) throws Exception {
        Path file = Files.write(directory.resolve(name), text.getBytes(Charset.forName(charset)));
        RecordingExchange exchange = new RecordingExchange();
        FileServeHandler.serveFile(exchange, file, null);
        assertEquals(200, exchange.status);
        assertTrue(exchange.headers.getFirst("Content-Type").endsWith("; charset=utf-8"));
        assertEquals(text, exchange.body.toString(StandardCharsets.UTF_8));
    }

    private static class RecordingExchange extends HttpExchange {
        final Headers headers = new Headers();
        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        int status;
        long length;
        @Override public Headers getRequestHeaders() { return new Headers(); }
        @Override public Headers getResponseHeaders() { return headers; }
        @Override public URI getRequestURI() { return URI.create("/api/file"); }
        @Override public String getRequestMethod() { return "GET"; }
        @Override public HttpContext getHttpContext() { return null; }
        @Override public void close() { }
        @Override public InputStream getRequestBody() { return InputStream.nullInputStream(); }
        @Override public OutputStream getResponseBody() { return body; }
        @Override public void sendResponseHeaders(int code, long length) { status = code; this.length = length; }
        @Override public InetSocketAddress getRemoteAddress() { return null; }
        @Override public InetSocketAddress getLocalAddress() { return null; }
        @Override public String getProtocol() { return "HTTP/1.1"; }
        @Override public Object getAttribute(String name) { return null; }
        @Override public void setAttribute(String name, Object value) { }
        @Override public void setStreams(InputStream input, OutputStream output) { }
        @Override public HttpPrincipal getPrincipal() { return null; }
        @Override public int getResponseCode() { return status; }
    }
}
