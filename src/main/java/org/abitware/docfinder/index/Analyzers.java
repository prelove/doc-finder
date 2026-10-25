package org.abitware.docfinder.index;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.analysis.ja.JapaneseAnalyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Analyzer factories that eagerly warm each analyzer's lazy dictionary.
 *
 * <p>Kuromoji (and SmartCN) dictionaries load on first tokenization, not at
 * analyzer construction. If that first load fails under memory pressure or
 * any other transient condition, the JVM marks the dictionary class
 * erroneous and every later tokenization reports
 * {@code NoClassDefFoundError} — permanently breaking search for the whole
 * session. Probing at construction time means: on success the dictionary is
 * warm for the rest of the session; on failure the real exception is logged
 * and the field falls back to {@link StandardAnalyzer} so search keeps
 * working with degraded segmentation instead of dying on every query.
 */
public final class Analyzers {
    private static final Logger log = LoggerFactory.getLogger(Analyzers.class);

    private Analyzers() {
    }

    public static Analyzer japanese() {
        return probe(new JapaneseAnalyzer(), "Japanese/Kuromoji");
    }

    public static Analyzer chinese() {
        return probe(new SmartChineseAnalyzer(), "Chinese/SmartCN");
    }

    private static Analyzer probe(Analyzer analyzer, String label) {
        try (TokenStream stream = analyzer.tokenStream("probe", "docfinder")) {
            stream.reset();
            while (stream.incrementToken()) {
                // drain tokens — this is what forces the dictionary load
            }
            stream.end();
            return analyzer;
        } catch (Throwable t) {
            log.error("{} analyzer failed to initialize — falling back to StandardAnalyzer "
                    + "for that field (this is the real cause; a lazy first-use failure would "
                    + "otherwise surface later as a permanent NoClassDefFoundError)", label, t);
            try {
                analyzer.close();
            } catch (Exception ignore) {
            }
            return new StandardAnalyzer();
        }
    }
}
