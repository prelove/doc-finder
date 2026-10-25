package org.abitware.docfinder.util;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * Lexical path normalization shared by the index field {@code path_scope},
 * source-root comparison and scoped queries. Windows folds to '/' + lower
 * case (Locale.ROOT); POSIX keeps case. No I/O: never calls toRealPath,
 * exists or probes UNC availability. Windows extended / device namespace
 * paths ({@code \\?\}, {@code \\.}) are explicitly rejected, never silently
 * mapped to normal paths.
 */
public final class PathScope {

    private PathScope() {
    }

    /**
     * Normalizes a Path obtained at index time.
     *
     * @return canonical scope string, or null when path is null
     * @throws IllegalArgumentException on extended namespace input
     */
    public static String normalizeIndexedPath(Path path) {
        if (path == null) {
            return null;
        }
        String s = path.toAbsolutePath().normalize().toString();
        if (!Utils.isWindows()) {
            return s;
        }
        s = Utils.canonicalizeWinPathString(s);
        rejectExtendedNamespace(s);
        return stripUncRootSlash(
                s.replace('\\', '/').toLowerCase(Locale.ROOT));
    }

    /**
     * Normalizes a user-supplied directory for a scoped query.
     *
     * @return normalized string, or null for null/blank input
     * @throws IllegalArgumentException when the trimmed value is not a legal
     *         absolute path on the host platform
     */
    public static String normalizeDirectory(String directoryPath) {
        if (directoryPath == null) {
            return null;
        }
        String raw = directoryPath.trim();
        if (raw.isEmpty()) {
            return null;
        }
        if (raw.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("directory path contains NUL");
        }
        raw = Utils.expandUserHome(raw); // Paths.get never expands ~
        Path p;
        if (Utils.isWindows()) {
            String canon = Utils.canonicalizeWinPathString(raw);
            rejectExtendedNamespace(canon);
            try {
                p = Paths.get(canon);
            } catch (InvalidPathException e) {
                throw new IllegalArgumentException("invalid directory path: " + e.getMessage(), e);
            }
            if (!p.isAbsolute()) {
                throw new IllegalArgumentException("directory path is not absolute: " + raw);
            }
            return stripUncRootSlash(
                    p.normalize().toString().replace('\\', '/').toLowerCase(Locale.ROOT));
        }
        try {
            p = Paths.get(raw);
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("invalid directory path: " + e.getMessage(), e);
        }
        if (!p.isAbsolute()) {
            throw new IllegalArgumentException("directory path is not absolute: " + raw);
        }
        return p.normalize().toString();
    }

    /**
     * Returns the descendant prefix of an already-normalized directory:
     * keeps an existing trailing '/', appends one otherwise. Never folds the
     * leading "//" of a normalized UNC path.
     */
    public static String descendantPrefix(String normalizedDirectory) {
        if (normalizedDirectory == null || normalizedDirectory.isEmpty()) {
            return null;
        }
        return normalizedDirectory.endsWith("/")
                ? normalizedDirectory
                : normalizedDirectory + "/";
    }

    /**
     * Java keeps the UNC root's trailing separator ({@code //server/share/});
     * the canonical scope form drops it so it matches the design table. Drive
     * roots ({@code e:/}) keep theirs because the slash is the root marker.
     */
    private static String stripUncRootSlash(String s) {
        if (!s.endsWith("/") || !s.startsWith("//")) {
            return s;
        }
        String t = s.substring(0, s.length() - 1);
        String rest = t.substring(2);
        int sep = rest.indexOf('/');
        // UNC root form "//server/share": exactly one '/' after the leading "//".
        if (sep > 0 && rest.indexOf('/', sep + 1) < 0) {
            return t;
        }
        return s;
    }

    private static void rejectExtendedNamespace(String canonicalWinPath) {
        if (canonicalWinPath.startsWith("\\\\?\\") || canonicalWinPath.startsWith("\\\\.\\")) {
            throw new IllegalArgumentException(
                    "extended/device namespace path is not supported: " + canonicalWinPath);
        }
    }
}
