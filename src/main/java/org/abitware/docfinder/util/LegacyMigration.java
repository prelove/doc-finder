package org.abitware.docfinder.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * One-time migration helper that copies data from the legacy {@code ~/.docfinder}
 * directory to the current application base directory returned by
 * {@link AppPaths#getBaseDir()}.
 *
 * <p>Migration is skipped when:
 * <ul>
 *   <li>The legacy directory does not exist, or</li>
 *   <li>A sentinel file {@code .migrated} already exists in the target directory.</li>
 * </ul>
 *
 * <p>After a successful migration the sentinel file is written so the operation
 * is never repeated.
 */
public final class LegacyMigration {

    private static final Logger log = LoggerFactory.getLogger(LegacyMigration.class);
    private static final String SENTINEL = ".migrated";

    private LegacyMigration() {}

    /**
     * Returns {@code true} if there is legacy data in {@code ~/.docfinder} that
     * has not yet been migrated to {@link AppPaths#getBaseDir()}.
     */
    public static boolean needsMigration() {
        Path legacy = legacyDir();
        Path target = AppPaths.getBaseDir();
        if (legacy == null || !Files.isDirectory(legacy)) {
            return false;
        }
        // Same directory – nothing to do.
        if (legacy.equals(target)) {
            return false;
        }
        // Already migrated previously.
        if (Files.exists(target.resolve(SENTINEL))) {
            return false;
        }
        // Check if the legacy directory has any content worth migrating.
        try {
            return Files.list(legacy).findAny().isPresent();
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Copies all files from {@code ~/.docfinder} to {@link AppPaths#getBaseDir()},
     * skipping files that already exist in the target.  Writes the sentinel file on
     * success.
     *
     * @return the number of files copied, or -1 on error
     */
    public static int migrate() {
        Path legacy = legacyDir();
        Path target = AppPaths.getBaseDir();
        if (legacy == null || !Files.isDirectory(legacy)) {
            return 0;
        }
        int[] count = {0};
        try {
            Files.createDirectories(target);
            Files.walkFileTree(legacy, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    Path rel = legacy.relativize(dir);
                    Path dest = target.resolve(rel);
                    Files.createDirectories(dest);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Path rel = legacy.relativize(file);
                    Path dest = target.resolve(rel);
                    if (!Files.exists(dest)) {
                        Files.copy(file, dest, StandardCopyOption.COPY_ATTRIBUTES);
                        count[0]++;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    log.warn("Migration: could not copy {}: {}", file, exc.getMessage());
                    return FileVisitResult.CONTINUE;
                }
            });
            // Write sentinel so we don't repeat migration.
            Files.write(target.resolve(SENTINEL), new byte[0]);
            log.info("Legacy migration complete: {} file(s) copied from {} to {}", count[0], legacy, target);
            return count[0];
        } catch (IOException e) {
            log.error("Legacy migration failed: {}", e.getMessage(), e);
            return -1;
        }
    }

    /** Returns the legacy data directory ({@code ~/.docfinder}), or {@code null} if unavailable. */
    private static Path legacyDir() {
        String home = System.getProperty("user.home");
        if (home == null || home.isEmpty()) {
            return null;
        }
        return Paths.get(home, ".docfinder").toAbsolutePath().normalize();
    }
}
