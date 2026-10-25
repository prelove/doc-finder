package org.abitware.docfinder.ui;

import java.util.logging.Level;
import java.util.logging.Logger;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;

import org.slf4j.LoggerFactory;

import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.NativeHookException;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener;


/** 全局热键注册：Ctrl + Alt + Space 呼出/隐藏窗口 */
public class GlobalHotkey implements NativeKeyListener {
    private static final org.slf4j.Logger log = LoggerFactory.getLogger(GlobalHotkey.class);
    private final JFrame target;
    private int hiddenExtendedState = JFrame.NORMAL;

    public GlobalHotkey(JFrame target) { this.target = target; }

    public void register() {
        // 关闭 JNativeHook 的 noisy 日志（只调本包级别，不重置整个 JVM 的 JUL）
        Logger.getLogger(GlobalScreen.class.getPackage().getName()).setLevel(Level.OFF);

        try {
            GlobalScreen.registerNativeHook();
            GlobalScreen.addNativeKeyListener(this);
        } catch (NativeHookException e) {
            // macOS without Accessibility permission lands here — hotkey is
            // optional, keep the app running but leave a diagnosable trail.
            log.warn("Global hotkey unavailable (OS hook registration failed; "
                    + "on macOS grant Accessibility/Input Monitoring permission): {}", e.toString());
        }
    }

    public void unregister() {
        try {
            GlobalScreen.removeNativeKeyListener(this);
            if (GlobalScreen.isNativeHookRegistered()) {
                GlobalScreen.unregisterNativeHook();
            }
        } catch (NativeHookException ignore) {}
    }

    @Override public void nativeKeyPressed(NativeKeyEvent e) {
        // Ctrl + Alt + Space
        boolean ctrl = (e.getModifiers() & NativeKeyEvent.CTRL_MASK) != 0;
        boolean alt  = (e.getModifiers() & NativeKeyEvent.ALT_MASK)  != 0;
        if (ctrl && alt && e.getKeyCode() == NativeKeyEvent.VC_SPACE) {
            SwingUtilities.invokeLater(() -> {
                boolean visible = target.isVisible();
                if (visible) {
                    hiddenExtendedState = target.getExtendedState();
                    target.setVisible(false);
                } else {
                    IconUtil.requestForegroundIfMac();
                    target.setVisible(true);
                    int restoreState = hiddenExtendedState == 0 ? JFrame.NORMAL : hiddenExtendedState;
                    target.setExtendedState(restoreState & ~JFrame.ICONIFIED);
                    target.toFront();
                    target.requestFocus();
                }
            });
        }
    }

    @Override public void nativeKeyReleased(NativeKeyEvent e) {}
    @Override public void nativeKeyTyped(NativeKeyEvent e) {}
}
