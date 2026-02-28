package org.abitware.docfinder.search;

import org.junit.jupiter.api.Test;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FilterStateTest {

    @Test
    void shouldParseEmptyString() {
        Set<String> exts = FilterState.parseExts("");
        assertTrue(exts.isEmpty());
    }

    @Test
    void shouldParseNullString() {
        Set<String> exts = FilterState.parseExts(null);
        assertTrue(exts.isEmpty());
    }

    @Test
    void shouldParseSingleExtension() {
        Set<String> exts = FilterState.parseExts("pdf");
        assertEquals(1, exts.size());
        assertTrue(exts.contains("pdf"));
    }

    @Test
    void shouldParseMultipleExtensions() {
        Set<String> exts = FilterState.parseExts("pdf,docx,txt");
        assertEquals(3, exts.size());
        assertTrue(exts.contains("pdf"));
        assertTrue(exts.contains("docx"));
        assertTrue(exts.contains("txt"));
    }

    @Test
    void shouldNormalizeToLowercase() {
        Set<String> exts = FilterState.parseExts("PDF,DOCX");
        assertTrue(exts.contains("pdf"));
        assertTrue(exts.contains("docx"));
    }

    @Test
    void shouldTrimWhitespace() {
        Set<String> exts = FilterState.parseExts(" pdf , docx , txt ");
        assertTrue(exts.contains("pdf"));
        assertTrue(exts.contains("docx"));
        assertTrue(exts.contains("txt"));
    }

    @Test
    void shouldIgnoreEmptyTokens() {
        Set<String> exts = FilterState.parseExts("pdf,,docx,");
        assertEquals(2, exts.size());
    }
}
