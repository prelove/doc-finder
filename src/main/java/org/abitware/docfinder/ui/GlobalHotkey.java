package org.abitware.docfinder.ui;

import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;

import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.NativeHookException;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener;


/** 全局热键注册：Ctrl + Alt + Space 呼出/隐藏窗口 */
public class GlobalHotkey implements NativeKeyListener {
    private final JFrame target;

    public GlobalHotkey(JFrame target) { this.target = target; }

    public void register() {
        // 关闭 JNativeHook 的 noisy 日志
        LogManager.getLogManager().reset();
        Logger.getLogger(GlobalScreen.class.getPackage().getName()).setLevel(Level.OFF);

        try {
            GlobalScreen.registerNativeHook();
            GlobalScreen.addNativeKeyListener(this);
        } catch (NativeHookException e) {
            e.printStackTrace();
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
                target.setVisible(!visible);
                if (!visible) {
                    target.setExtendedState(JFrame.NORMAL);
                    target.toFront();
                    target.requestFocus();
                }
            });
        }
    }

    @Override public void nativeKeyReleased(NativeKeyEvent e) {}
    @Override public void nativeKeyTyped(NativeKeyEvent e) {}
}
