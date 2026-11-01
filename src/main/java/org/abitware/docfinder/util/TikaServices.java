package org.abitware.docfinder.util;

import org.apache.tika.detect.DefaultEncodingDetector;
import org.apache.tika.detect.EncodingDetector;
import org.apache.tika.parser.AutoDetectParser;

/** Shared, lazily initialized Tika services for web and desktop previews. */
public final class TikaServices {
    private TikaServices() { }

    private static final class DetectorHolder {
        // 检测器构造会加载统计模型；共享实例避免每次预览都支付模型加载成本。
        private static final EncodingDetector INSTANCE = new DefaultEncodingDetector();
    }

    private static final class PreviewParserHolder {
        private static final AutoDetectParser INSTANCE = new AutoDetectParser();
    }

    public static EncodingDetector encodingDetector() {
        return DetectorHolder.INSTANCE;
    }

    public static AutoDetectParser previewParser() {
        return PreviewParserHolder.INSTANCE;
    }
}
