package org.abitware.docfinder.util;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class Utils {
    private Utils() {}

    public static boolean isWindows() {
        String os = System.getProperty("os.name","").toLowerCase();
        return os.contains("win");
    }
    public static String canonicalizeWinPathString(String s) {
        if (s == null) return "";
        String t = s.trim();
        t = t.replace('\u00A5', '\\'); // ¥
        t = t.replace('\uFF3C', '\\'); // ＼
        t = t.replace('\uFF0F', '/');  // ／
        t = t.replace('/', '\\');
        return t;
    }
    public static boolean isUncPrefix(String raw) {
        String s = canonicalizeWinPathString(raw);
        return s.startsWith("\\\\?\\UNC\\") || (s.length() >= 2 && s.charAt(0) == '\\' && s.charAt(1) == '\\');
    }

    // —— 驱动器缓存 & 外部命令工具 —— //
    private static final Map<Character, Boolean> DRIVE_REMOTE_CACHE = new ConcurrentHashMap<>();
    private static class Worker extends Thread { private final Process p; Integer exit; Worker(Process p){this.p=p;setDaemon(true);} public void run(){try{exit=p.waitFor();}catch(InterruptedException ignored){}} }
    private static String runCmd(String[] cmd, int timeoutMs) {
        Process p = null;
        try {
            p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            Worker w = new Worker(p); w.start(); w.join(timeoutMs);
            if (w.exit == null) { p.destroyForcibly(); return null; }
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"))) {
                StringBuilder sb = new StringBuilder(); String line;
                while ((line = br.readLine()) != null) sb.append(line).append('\n');
                return sb.toString();
            }
        } catch (Exception ignore) {
            if (p != null) try { p.destroyForcibly(); } catch (Exception ignored) {}
            return null;
        }
    }
    private static Character getDriveLetter(Path p) {
        try {
            String root = p.toAbsolutePath().getRoot().toString();
            if (root != null && root.length() >= 2 && Character.isLetter(root.charAt(0)) && root.charAt(1) == ':') {
                return Character.toUpperCase(root.charAt(0));
            }
        } catch (Exception ignore) {}
        return null;
    }
    private static boolean isMappedNetworkDriveWindows(char driveLetter) {
        String ps = "(Get-PSDrive -Name '" + driveLetter + "').DisplayRoot";
        String out = runCmd(new String[]{"powershell", "-NoProfile", "-NonInteractive", "-Command", ps}, 2000);
        if (out != null && out.trim().startsWith("\\\\")) return true;
        String out2 = runCmd(new String[]{"cmd", "/c", "net", "use", driveLetter + ":"}, 2000);
        if (out2 != null && out2.contains("\\\\")) return true;
        String wmic = "wmic logicaldisk where (DeviceID='" + driveLetter + ":') get DriveType /value";
        String out3 = runCmd(new String[]{"cmd", "/c", wmic}, 2000);
        if (out3 != null && out3.replace("\r","").contains("DriveType=4")) return true;
        return false;
    }

    /** 入口：更稳的“疑似网络路径”判断（UNC / 映射盘 / FileStore 回退） */
    public static boolean isLikelyNetwork(Path p) {
        if (p == null) return false;
        if (!isWindows()) {
            try {
                String type = java.nio.file.Files.getFileStore(p).type().toLowerCase(Locale.ROOT);
                return type.contains("nfs") || type.contains("cifs") || type.contains("smb")
                    || type.contains("smbfs") || type.contains("afp") || type.contains("afpfs")
                    || type.contains("webdav") || type.contains("fuse") || type.contains("sshfs");
            } catch (Exception ignore) { return false; }
        }
        String s = p.toAbsolutePath().toString();
        if (isUncPrefix(s)) return true;
        Character drv = getDriveLetter(p);
        if (drv != null) {
            Boolean cached = DRIVE_REMOTE_CACHE.get(drv);
            if (cached != null) return cached;
            boolean remote = isMappedNetworkDriveWindows(drv);
            DRIVE_REMOTE_CACHE.put(drv, remote);
            if (remote) return true;
        }
        try {
            java.nio.file.FileStore fs = java.nio.file.Files.getFileStore(p);
            String type = String.valueOf(fs.type()).toLowerCase(Locale.ROOT);
            String name = String.valueOf(fs.name()).toLowerCase(Locale.ROOT);
            return name.startsWith("\\\\") || type.contains("cifs") || type.contains("smb") || type.contains("webdav");
        } catch (Exception ignore) { return false; }
    }

    /** 索引用统一路径字符串（含日文 \ 表示兼容） */
    public static String normalizeForIndex(Path p) {
        String s = p.toAbsolutePath().toString();
        if (isWindows()) s = canonicalizeWinPathString(s);
        return s;
    }
    /** 供 Explorer 友好展示/打开 */
    public static String toExplorerFriendlyPath(String s) {
        if (!isWindows()) return s;
        String t = canonicalizeWinPathString(s);
        if (t.startsWith("\\\\?\\UNC\\")) t = "\\" + t.substring("\\\\?\\UNC".length());
        return t;
    }
}
