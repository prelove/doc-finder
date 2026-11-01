package org.abitware.docfinder.ui;

import org.apache.tika.detect.EncodingDetector;
import org.apache.tika.detect.EncodingResult;
import org.abitware.docfinder.util.TikaServices;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MainWindowCharsetTest {
    @TempDir Path directory;

    @Test
    void shouldOfferDetectedCyrillicCharsetBeforeFallbacks() throws Exception {
        Charset charset = Charset.forName("windows-1251");
        Path file = Files.write(directory.resolve("russian.txt"),
                ("Это русский текст для проверки определения кодировки. "
                + "Поиск документов должен сохранять содержимое. ").repeat(20).getBytes(charset));
        List<Charset> candidates = MainWindow.detectCandidateCharsets(file, TikaServices.encodingDetector());
        assertTrue(candidates.contains(charset));
        assertTrue(candidates.indexOf(charset) < candidates.indexOf(StandardCharsets.UTF_8));
    }

    @Test
    void shouldRejectBelowDesktopThresholdAndAcceptBoundaryInOrder() throws Exception {
        Path file = Files.write(directory.resolve("ascii.txt"), new byte[] {65});
        EncodingDetector detector = (stream, metadata, context) -> Arrays.asList(
                null, new EncodingResult(StandardCharsets.UTF_8, 1.0f) {
                    @Override public Charset getCharset() { return null; }
                },
                new EncodingResult(Charset.forName("KOI8-R"), 0.29f),
                new EncodingResult(Charset.forName("windows-1251"), 0.30f),
                new EncodingResult(Charset.forName("Shift_JIS"), 0.80f),
                new EncodingResult(Charset.forName("windows-31j"), 0.90f));
        List<Charset> candidates = MainWindow.detectCandidateCharsets(file, detector);
        assertFalse(candidates.contains(Charset.forName("KOI8-R")));
        assertEquals(Arrays.asList(Charset.forName("windows-1251"), Charset.forName("windows-31j"),
                StandardCharsets.UTF_8), candidates.subList(0, 3));
        assertEquals(1, candidates.stream().filter(Charset.forName("windows-31j")::equals).count());
    }

    @Test
    void shouldPutContradictoryBomBeforeDetectorCandidates() throws Exception {
        byte[] body = "中文文件编码".getBytes(Charset.forName("GBK"));
        byte[] bytes = new byte[body.length + 3];
        bytes[0] = (byte) 0xEF; bytes[1] = (byte) 0xBB; bytes[2] = (byte) 0xBF;
        System.arraycopy(body, 0, bytes, 3, body.length);
        Path file = Files.write(directory.resolve("contradictory.txt"), bytes);
        List<Charset> candidates = MainWindow.detectCandidateCharsets(file,
                (stream, metadata, context) -> Arrays.asList(new EncodingResult(Charset.forName("GBK"), 1.0f)));
        assertEquals(StandardCharsets.UTF_8, candidates.get(0));
        assertEquals(Charset.forName("GB18030"), candidates.get(1));
    }

    @Test
    void shouldKeepUtf8FirstWhenDetectorReturnsNoCandidates() throws Exception {
        Path file = Files.write(directory.resolve("ascii.txt"), new byte[] {65});
        assertEquals(StandardCharsets.UTF_8, MainWindow.detectCandidateCharsets(file,
                (stream, metadata, context) -> null).get(0));
    }

    @Test
    void shouldDecodeCp932ExtensionsWithDetectedCandidate() throws Exception {
        String text = "会議資料①～③…㈱ 検索文書の内容を確認します。 ".repeat(20);
        byte[] bytes = text.getBytes(Charset.forName("windows-31j"));
        Path file = Files.write(directory.resolve("cp932.txt"), bytes);
        List<Charset> candidates = MainWindow.detectCandidateCharsets(file, TikaServices.encodingDetector());
        assertEquals(text, new String(bytes, candidates.get(0)));
    }
}
