package org.abitware.docfinder.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LegacyMigration must run against @TempDir fixtures only. user.home and
 * user.dir are pointed at temporary siblings and restored after each test so
 * the real ~/.docfinder and the real working directory are never touched.
 */
@Execution(ExecutionMode.SAME_THREAD)
class LegacyMigrationTest {

    private String origHome;
    private String origDir;

    @TempDir
    Path tempHome;

    @TempDir
    Path tempWork;

    private Path legacyDir;
    private Path baseDir;

    @BeforeEach
    void saveProps() {
        origHome = System.getProperty("user.home");
        origDir = System.getProperty("user.dir");
        System.setProperty("user.home", tempHome.toAbsolutePath().toString());
        System.setProperty("user.dir", tempWork.toAbsolutePath().toString());
        legacyDir = tempHome.resolve(".docfinder");
        baseDir = tempWork.resolve(".docfinder");
    }

    @AfterEach
    void restoreProps() {
        if (origHome != null) {
            System.setProperty("user.home", origHome);
        }
        if (origDir != null) {
            System.setProperty("user.dir", origDir);
        }
    }

    @Test
    void shouldNotMigrateWhenLegacyDirMissing() {
        assertFalse(LegacyMigration.needsMigration());
        assertEquals(0, LegacyMigration.migrate());
        assertFalse(Files.exists(baseDir), "missing legacy dir must not create a data dir");
    }

    @Test
    void shouldNotMigrateWhenLegacyDirEmpty() throws Exception {
        Files.createDirectories(legacyDir);
        assertFalse(LegacyMigration.needsMigration());
        assertEquals(0, LegacyMigration.migrate());
    }

    @Test
    void shouldCopyLegacyFilesAndWriteSentinel() throws Exception {
        Files.createDirectories(legacyDir.resolve("sub"));
        Files.write(legacyDir.resolve("config.properties"), "a=1".getBytes("UTF-8"));
        Files.write(legacyDir.resolve("sub").resolve("note.txt"), "x".getBytes("UTF-8"));

        assertTrue(LegacyMigration.needsMigration());
        int copied = LegacyMigration.migrate();

        assertEquals(2, copied);
        assertArrayEquals("a=1".getBytes("UTF-8"),
                Files.readAllBytes(baseDir.resolve("config.properties")));
        assertArrayEquals("x".getBytes("UTF-8"),
                Files.readAllBytes(Paths.get(baseDir.toString(), "sub", "note.txt")));
        assertTrue(Files.exists(baseDir.resolve(".migrated")), "sentinel must be written");
        assertFalse(LegacyMigration.needsMigration(), "sentinel must stop further migration");
    }

    @Test
    void shouldNotOverwriteExistingTargetFiles() throws Exception {
        Files.createDirectories(legacyDir);
        Files.write(legacyDir.resolve("config.properties"), "new".getBytes("UTF-8"));
        Files.write(legacyDir.resolve("history.txt"), "legacy-hist".getBytes("UTF-8"));

        Files.createDirectories(baseDir);
        Files.write(baseDir.resolve("config.properties"), "keep".getBytes("UTF-8"));

        int copied = LegacyMigration.migrate();

        assertEquals(1, copied, "only the missing file should be copied");
        assertArrayEquals("keep".getBytes("UTF-8"),
                Files.readAllBytes(baseDir.resolve("config.properties")));
        assertArrayEquals("legacy-hist".getBytes("UTF-8"),
                Files.readAllBytes(baseDir.resolve("history.txt")));
        assertTrue(Files.exists(baseDir.resolve(".migrated")));
    }

    @Test
    void shouldSkipWhenSentinelExists() throws Exception {
        Files.createDirectories(legacyDir);
        Files.write(legacyDir.resolve("config.properties"), "new".getBytes("UTF-8"));
        Files.createDirectories(baseDir);
        Files.write(baseDir.resolve(".migrated"), new byte[0]);

        // Production contract: App only calls migrate() when needsMigration()
        // is true. The sentinel guards that decision.
        assertFalse(LegacyMigration.needsMigration());
    }
}
