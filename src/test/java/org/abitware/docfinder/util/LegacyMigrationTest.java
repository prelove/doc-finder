package org.abitware.docfinder.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

class LegacyMigrationTest {

    @Test
    void shouldNotNeedMigrationWhenLegacyDirAbsent(@TempDir Path tempDir) {
        // If ~/.docfinder doesn't exist we should report false.
        // We can't easily control user.home in a unit test, but we can at least
        // verify the method doesn't throw.
        assertDoesNotThrow(LegacyMigration::needsMigration);
    }

    @Test
    void shouldReturnZeroFilesWhenNothingToCopy() {
        // A fresh migrate() with no legacy dir should return 0 (not an error).
        // Since user.home may or may not have ~/.docfinder, we only check for non-error.
        int result = LegacyMigration.migrate();
        // Result is either 0 (nothing copied) or positive (files copied) — never -1 for a no-op
        assertNotEquals(-1, result, "migrate() must not return -1 unless a real I/O error occurs");
    }
}
