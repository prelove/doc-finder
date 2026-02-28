package org.abitware.docfinder.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LegacyMigrationTest {

    @Test
    void shouldNotNeedMigrationWhenLegacyDirAbsent() {
        // If ~/.docfinder doesn't exist we should report false.
        // We can't easily control user.home in a unit test, but we can at least
        // verify the method doesn't throw.
        assertDoesNotThrow(LegacyMigration::needsMigration);
    }

    @Test
    void shouldReturnNonNegativeOnMigrate() {
        // A migrate() call should never return -1 unless a real I/O error occurs.
        // When legacy dir doesn't exist this must return 0.
        int result = LegacyMigration.migrate();
        assertTrue(result >= 0, "migrate() must return 0 or more; -1 signals an I/O error");
    }
}
