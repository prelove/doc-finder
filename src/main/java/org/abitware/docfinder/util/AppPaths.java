package org.abitware.docfinder.util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class AppPaths {
    private AppPaths() {
    }

    /**
     * Application data directory: {@code <user.dir>/.docfinder} when that
     * location is usable, otherwise {@code <user.home>/.docfinder}.
     *
     * <p>{@code user.dir} is not a usable parent when it is the filesystem
     * root (macOS .app bundles launch with cwd=/) or not writable (install
     * dir under Program Files). Falling back to the home directory keeps a
     * packaged/launched app functional instead of silently running without
     * config, sources or index.
     */
    public static Path getBaseDir() {
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path candidate = cwd.resolve(".docfinder");
        if (usable(candidate, cwd)) {
            return candidate;
        }
        String home = System.getProperty("user.home");
        if (home != null && !home.isEmpty()) {
            Path fallback = Paths.get(home, ".docfinder").toAbsolutePath().normalize();
            if (!fallback.equals(candidate)) {
                return fallback;
            }
        }
        return candidate; // no better option — keep the deterministic location
    }

    private static boolean usable(Path candidate, Path cwd) {
        if (cwd.getParent() == null) {
            return false; // cwd is the filesystem root — never write at /
        }
        if (Files.exists(candidate)) {
            return Files.isWritable(candidate);
        }
        return Files.isWritable(cwd);
    }
}

