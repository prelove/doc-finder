package org.abitware.docfinder.ui;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 图标加载&适配（支持多尺寸、托盘、macOS Dock）。 */
public final class IconUtil {
    private IconUtil() {}

    private static final String BASE = "/icons/";
    private static final List<Integer> APP_SIZES = Arrays.asList(16, 24, 32, 48, 64, 128, 256);

    /** 加载应用多尺寸图标列表；缺失尺寸会从 app.png 自动缩放。 */
    public static List<Image> loadAppImages() {
        List<Image> out = new ArrayList<>();
        Image base = loadResource(BASE + "app.png"); // 作为缩放源
        for (int sz : APP_SIZES) {
            Image img = loadResource(BASE + "app-" + sz + ".png");
            if (img == null && base != null) img = scale(base, sz, sz);
            if (img != null) out.add(img);
        }
        // 兜底：若都没有，返回空列表
        return out;
    }

    /** 加载托盘图标（优先 tray-16，其次 app-16，再次缩放 app.png）。 */
    public static Image loadTrayImage() {
        Image img = loadResource(BASE + "tray-16.png");
        if (img == null) img = loadResource(BASE + "app-16.png");
        if (img == null) {
            Image base = loadResource(BASE + "app.png");
            if (base != null) img = scale(base, 16, 16);
        }
        return img;
    }

    /** 尝试设置任务栏/Dock 图标（Java 9+ Taskbar；macOS Java 8 走反射）。 */
    public static void setAppTaskbarIconIfSupported(Image best) {
        if (best == null) return;
        // Java 9+: Taskbar
        try {
            Class<?> tbClass = Class.forName("java.awt.Taskbar");
            Object tb = tbClass.getMethod("getTaskbar").invoke(null);
            tbClass.getMethod("setIconImage", Image.class).invoke(tb, best);
            return;
        } catch (Throwable ignored) { /* 继续试 macOS 旧 API */ }
        // macOS (Apple JDK / Oracle JDK 8) 的 com.apple.eawt.Application
        try {
            if (!System.getProperty("os.name","").toLowerCase().contains("mac")) return;
            Class<?> appClass = Class.forName("com.apple.eawt.Application");
            Object app = appClass.getMethod("getApplication").invoke(null);
            appClass.getMethod("setDockIconImage", Image.class).invoke(app, best);
        } catch (Throwable ignored) { /* 无视 */ }
    }

    /** 从资源读 PNG。 */
    private static Image loadResource(String path) {
        try (InputStream in = IconUtil.class.getResourceAsStream(path)) {
            if (in == null) return null;
            return ImageIO.read(in);
        } catch (Exception e) { return null; }
    }

    /** 高质量缩放。 */
    private static Image scale(Image src, int w, int h) {
        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = dst.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return dst;
    }
}
