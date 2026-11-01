package org.abitware.docfinder.web;

import org.junit.jupiter.api.Test;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TextServeHandlerTest {
    private static final String RUSSIAN_TEXT =
            "Это русский текст для проверки определения кодировки. Поиск документов должен сохранять содержимое. ";

    @Test
    void shouldDecodeLegacyCyrillicWithoutFallingBackToLatin1() {
        String text = RUSSIAN_TEXT.repeat(20);
        byte[] bytes = text.getBytes(Charset.forName("windows-1251"));
        Charset detected = TextServeHandler.detectCharset(bytes, bytes.length);
        assertEquals(text, new String(bytes, detected));
    }

    @Test
    void shouldPreferBomOverStatisticalDetection() {
        byte[] body = "中文编码测试".repeat(20).getBytes(Charset.forName("GBK"));
        byte[] bytes = new byte[body.length + 3];
        bytes[0] = (byte) 0xEF; bytes[1] = (byte) 0xBB; bytes[2] = (byte) 0xBF;
        System.arraycopy(body, 0, bytes, 3, body.length);
        assertEquals(StandardCharsets.UTF_8, TextServeHandler.detectCharset(bytes, bytes.length));
        assertEquals(StandardCharsets.UTF_8, TextServeHandler.detectCharset(bytes, bytes.length,
                (stream, metadata, context) -> java.util.Arrays.asList(
                        new org.apache.tika.detect.EncodingResult(Charset.forName("GBK"), 1.0f))));
    }

    @Test
    void shouldKeepUtf8ForEmptyInput() {
        assertEquals(StandardCharsets.UTF_8, TextServeHandler.detectCharset(new byte[0], 0));
    }
    @Test
    void shouldDecodeCp932Extensions() {
        assertRoundTrip("会議資料①～③…㈱ 検索文書の内容を確認します。 ".repeat(20), "windows-31j");
    }

    @Test
    void shouldDecodeCjkText() {
        assertRoundTrip("中文文件内容编码检测，搜索文档应该保持原始内容。 ".repeat(20), "UTF-8");
        assertRoundTrip("中文文件内容编码检测，搜索文档应该保持原始内容。 ".repeat(20), "GBK");
        assertRoundTrip("日本語の文書を検索して内容を確認します。 ".repeat(20), "Shift_JIS");
        assertRoundTrip("한글 문서의 내용을 검색하고 문자 인코딩을 확인합니다. ".repeat(20), "EUC-KR");
    }

    @Test
    void shouldKeepUtf8ForAscii() {
        byte[] bytes = "plain ASCII content".getBytes(StandardCharsets.US_ASCII);
        assertEquals(StandardCharsets.UTF_8, TextServeHandler.detectCharset(bytes, bytes.length));
    }

    @Test
    void shouldDetectTwentyTimesWithinTwoSecondsAfterWarmup() {
        byte[] bytes = RUSSIAN_TEXT.repeat(20).getBytes(Charset.forName("windows-1251"));
        TextServeHandler.detectCharset(bytes, bytes.length);
        long start = System.nanoTime();
        for (int i = 0; i < 20; i++) {
            assertEquals(Charset.forName("windows-1251"), TextServeHandler.detectCharset(bytes, bytes.length));
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        System.out.println("Twenty warmed charset detections: " + elapsedMs + " ms");
        assertTrue(elapsedMs < 2000, "reused detector must avoid model loading per request: " + elapsedMs);
    }

    @Test
    void shouldRejectBelowWebThresholdAndAcceptBoundaryInOrder() throws Exception {
        byte[] bytes = "ascii".getBytes(StandardCharsets.US_ASCII);
        org.apache.tika.detect.EncodingDetector detector = (stream, metadata, context) -> java.util.Arrays.asList(
                null,
                new org.apache.tika.detect.EncodingResult(StandardCharsets.UTF_8, 1.0f) {
                    @Override public Charset getCharset() { return null; }
                },
                new org.apache.tika.detect.EncodingResult(Charset.forName("windows-1251"), 0.19f),
                new org.apache.tika.detect.EncodingResult(Charset.forName("Shift_JIS"), 0.20f),
                new org.apache.tika.detect.EncodingResult(StandardCharsets.UTF_8, 0.80f),
                new org.apache.tika.detect.EncodingResult(Charset.forName("windows-31j"), 1.0f));
        assertEquals(java.util.Arrays.asList(Charset.forName("windows-31j"), StandardCharsets.UTF_8),
                TextServeHandler.detectCandidateCharsets(bytes, bytes.length, detector));
    }

    @Test
    void shouldAllowFallbackWhenDetectorReturnsNoCandidates() throws Exception {
        byte[] bytes = {65};
        assertTrue(TextServeHandler.detectCandidateCharsets(bytes, bytes.length,
                (stream, metadata, context) -> null).isEmpty());
    }

    private static void assertRoundTrip(String text, String charset) {
        byte[] bytes = text.getBytes(Charset.forName(charset));
        assertEquals(text, new String(bytes, TextServeHandler.detectCharset(bytes, bytes.length)), charset);
    }
}
