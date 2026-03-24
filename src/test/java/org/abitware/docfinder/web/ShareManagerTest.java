package org.abitware.docfinder.web;

import org.junit.jupiter.api.*;
import java.io.IOException;
import java.nio.file.*;

import static org.junit.jupiter.api.Assertions.*;

class ShareManagerTest {

    private Path tempDir;
    private ShareManager manager;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("sharemanager-test");
        manager = new ShareManager(tempDir);
    }

    @AfterEach
    void tearDown() throws IOException {
        // Clean up temp files
        if (Files.exists(tempDir)) {
            Files.walk(tempDir)
                 .sorted(java.util.Comparator.reverseOrder())
                 .map(Path::toFile)
                 .forEach(java.io.File::delete);
        }
    }

    @Test
    void shouldCreateShareWithToken() {
        ShareManager.ShareEntry e = manager.createShare("/tmp/file.pdf", 24, null, 0);
        assertNotNull(e.token);
        assertFalse(e.token.isEmpty());
        assertEquals("/tmp/file.pdf", e.filePath);
        assertNull(e.passwordHash);
        assertEquals(0, e.downloadCount);
    }

    @Test
    void shouldCreateShareWithPassword() {
        ShareManager.ShareEntry e = manager.createShare("/tmp/doc.docx", 24, "secret123", 0);
        assertNotNull(e.passwordHash);
        assertNotEquals("secret123", e.passwordHash); // must be hashed
        assertEquals(ShareManager.sha256("secret123"), e.passwordHash);
    }

    @Test
    void shouldValidateShareWithCorrectPassword() {
        manager.createShare("/tmp/test.txt", 24, "pass", 0);
        ShareManager.ShareEntry e = manager.listShares().get(0);
        assertTrue(manager.isValid(e.token, "pass"));
    }

    @Test
    void shouldRejectShareWithWrongPassword() {
        manager.createShare("/tmp/test.txt", 24, "pass", 0);
        ShareManager.ShareEntry e = manager.listShares().get(0);
        assertFalse(manager.isValid(e.token, "wrong"));
    }

    @Test
    void shouldValidateShareWithNoPassword() {
        manager.createShare("/tmp/test.txt", 24, null, 0);
        ShareManager.ShareEntry e = manager.listShares().get(0);
        assertTrue(manager.isValid(e.token, null));
        assertTrue(manager.isValid(e.token, ""));
        assertTrue(manager.isValid(e.token, "anything")); // no password required
    }

    @Test
    void shouldDetectExpiredShare() {
        ShareManager.ShareEntry e = manager.createShare("/tmp/test.txt", -1, null, 0);
        // expiryHours <= 0 → never expires
        assertFalse(manager.isExpired(e));
    }

    @Test
    void shouldExpireImmediately() {
        // Create a share that expired in the past by manipulating expiresAt
        ShareManager.ShareEntry e = manager.createShare("/tmp/test.txt", 1, null, 0);
        e.expiresAt = System.currentTimeMillis() - 1000; // artificially in the past
        assertTrue(manager.isExpired(e));
    }

    @Test
    void shouldRevokeShare() {
        ShareManager.ShareEntry e = manager.createShare("/tmp/test.txt", 24, null, 0);
        String token = e.token;
        assertNotNull(manager.getShare(token));
        manager.revokeShare(token);
        assertNull(manager.getShare(token));
    }

    @Test
    void shouldReturnNullForUnknownToken() {
        assertNull(manager.getShare("nonexistent-token"));
    }

    @Test
    void shouldRecordDownloads() {
        ShareManager.ShareEntry e = manager.createShare("/tmp/test.txt", 24, null, 0);
        assertEquals(0, e.downloadCount);
        manager.recordDownload(e.token);
        manager.recordDownload(e.token);
        ShareManager.ShareEntry updated = manager.getShare(e.token);
        assertEquals(2, updated.downloadCount);
    }

    @Test
    void shouldEnforceMaxDownloads() {
        ShareManager.ShareEntry e = manager.createShare("/tmp/test.txt", 24, null, 2);
        assertTrue(manager.isValid(e.token, null));
        manager.recordDownload(e.token);
        manager.recordDownload(e.token);
        assertFalse(manager.isValid(e.token, null)); // cap reached
    }

    @Test
    void shouldPersistAndReload() {
        manager.createShare("/tmp/persist.txt", 24, "pw", 5);
        String token = manager.listShares().get(0).token;

        // Create a new manager reading the same file
        ShareManager reloaded = new ShareManager(tempDir);
        ShareManager.ShareEntry e = reloaded.getShare(token);
        assertNotNull(e);
        assertEquals("/tmp/persist.txt", e.filePath);
        assertEquals(5, e.maxDownloads);
        assertEquals(ShareManager.sha256("pw"), e.passwordHash);
    }

    @Test
    void shouldListMultipleShares() {
        manager.createShare("/tmp/a.txt", 24, null, 0);
        manager.createShare("/tmp/b.txt", 24, null, 0);
        manager.createShare("/tmp/c.txt", 24, null, 0);
        assertEquals(3, manager.listShares().size());
    }

    @Test
    void shouldCleanExpiredEntries() {
        ShareManager.ShareEntry e1 = manager.createShare("/tmp/a.txt", 24, null, 0);
        ShareManager.ShareEntry e2 = manager.createShare("/tmp/b.txt", 24, null, 0);
        e2.expiresAt = System.currentTimeMillis() - 1; // expired
        manager.cleanExpired();
        assertNotNull(manager.getShare(e1.token));
        assertNull(manager.getShare(e2.token));
    }

    @Test
    void shouldRequirePasswordOnlyWhenSet() {
        manager.createShare("/tmp/protected.txt", 24, "pw", 0);
        manager.createShare("/tmp/open.txt", 24, null, 0);
        ShareManager.ShareEntry prot = manager.listShares().get(0);
        ShareManager.ShareEntry open = manager.listShares().get(1);
        assertTrue(manager.requiresPassword(prot.token));
        assertFalse(manager.requiresPassword(open.token));
    }
}
