package org.abitware.docfinder.ui;

import com.formdev.flatlaf.FlatDarculaLaf;
import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatIntelliJLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import com.formdev.flatlaf.intellijthemes.FlatAllIJThemes;

import javax.swing.*;
import java.util.prefs.Preferences;

/** 主题管理：应用/记忆主题 + 动态构建“Theme”菜单 */
public final class ThemeUtil {
    private ThemeUtil() {}

    private static final Preferences PREF = Preferences.userNodeForPackage(ThemeUtil.class);
    private static final String KEY_LAF_CLASS = "lafClassName";

    /** 启动时应用上次选中的主题（找不到则用 Flat Light） */
    public static void initLafOnStartup() {
        String def = FlatLightLaf.class.getName();
        String lafClass = PREF.get(KEY_LAF_CLASS, def);
        apply(lafClass, false);
    }

    /** 应用某个 LAF（可选带动画）。失败则回退 Flat Light。 */
    public static void apply(String lafClassName, boolean animate) {
        // 动画快照（如果引入了 flatlaf-extras）
        try { if (animate) com.formdev.flatlaf.extras.FlatAnimatedLafChange.showSnapshot(); } catch (Throwable ignore) {}

        try {
            UIManager.setLookAndFeel(lafClassName);
        } catch (Throwable ex) {
            try { UIManager.setLookAndFeel(FlatLightLaf.class.getName()); } catch (Exception ignore) {}
        }

        // 刷新全局 UI
        try { FlatLaf.updateUI(); } catch (Throwable ignore) {}

        // 记住选择
        PREF.put(KEY_LAF_CLASS, UIManager.getLookAndFeel().getClass().getName());

        // 动画关闭
        try { if (animate) com.formdev.flatlaf.extras.FlatAnimatedLafChange.hideSnapshotWithAnimation(); } catch (Throwable ignore) {}
    }

    /** 构建完整的 “Theme” 菜单：内置 + IntelliJ 主题全集 */
    public static JMenu buildThemeMenu() {
        JMenu menu = new JMenu("Theme");
        ButtonGroup group = new ButtonGroup();
        String current = UIManager.getLookAndFeel().getClass().getName();

        // --- 内置四套 ---
        addThemeItem(menu, group, "Flat Light", FlatLightLaf.class.getName(), current);
        addThemeItem(menu, group, "Flat Dark",  FlatDarkLaf.class.getName(),  current);
        addThemeItem(menu, group, "IntelliJ Light", FlatIntelliJLaf.class.getName(), current);
        addThemeItem(menu, group, "Darcula", FlatDarculaLaf.class.getName(), current);

        menu.addSeparator();

        // --- IntelliJ 主题全集（改这里：用 UIManager.LookAndFeelInfo） ---
        for (UIManager.LookAndFeelInfo info : FlatAllIJThemes.INFOS) {
            addThemeItem(menu, group, info.getName(), info.getClassName(), current);
        }
        return menu;
    }

    private static void addThemeItem(JMenu menu, ButtonGroup group, String name, String className, String current) {
        boolean selected = className.equals(current);
        JRadioButtonMenuItem it = new JRadioButtonMenuItem(name, selected);
        it.addActionListener(e -> apply(className, true));
        group.add(it);
        menu.add(it);
    }
}
