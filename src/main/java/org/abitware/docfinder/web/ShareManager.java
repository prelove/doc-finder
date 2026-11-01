package org.abitware.docfinder.web;

import org.abitware.docfinder.util.AppPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Manages file share tokens persisted to {@code ~/.docfinder/shares.properties}.
 * Each share has a UUID token, an absolute file path, optional expiry, optional
 * password (SHA-256 hashed), and an optional max-download cap.
 *
 * <p>Thread-safety: all public methods hold {@link #sharesLock} so the instance
 * is safe to use from concurrent HTTP handler threads.
 */
public class ShareManager {

    private static final Logger log = LoggerFactory.getLogger(ShareManager.class);

    // ---- Value object -------------------------------------------------------

    public static class ShareEntry {
        public String token;
        public String filePath;
        /** Epoch millis; 0 = never expires */
        public long expiresAt;
        /** SHA-256 hex of the password; {@code null} = no password required */
        public String passwordHash;
        public long createdAt;
        public int downloadCount;
        /** 0 = unlimited */
        public int maxDownloads;
    }

    // ---- State --------------------------------------------------------------

    private final Path sharesFile;
    // 写路径（createShare/recordDownload/revokeShare/cleanExpired）持锁 save
    // 到磁盘；读路径虽主要是内存操作，也必须与写方共用同一把锁。虚拟线程
    // 在 monitor 内阻塞 I/O 会固定载体线程，故用 ReentrantLock；锁可重入，
    // isValid/requiresPassword 内嵌套 getShare 不会死锁。
    private final ReentrantLock sharesLock = new ReentrantLock();
    // token → entry; linked so list order is insertion order
    private final Map<String, ShareEntry> shares = new LinkedHashMap<>();

    // ---- Construction -------------------------------------------------------

    public ShareManager() {
        this(AppPaths.getBaseDir());
    }

    public ShareManager(Path baseDir) {
        this.sharesFile = baseDir.resolve("shares.properties");
        load();
    }

    // ---- Public API ---------------------------------------------------------

    /**
     * Creates a new share.
     *
     * @param filePath      absolute path to the file being shared
     * @param expiryHours   hours until expiry; {@code <= 0} means never
     * @param password      plain-text password; {@code null} or empty = no password
     * @param maxDownloads  maximum number of downloads; {@code <= 0} = unlimited
     * @return the created entry
     */
    public ShareEntry createShare(String filePath, int expiryHours,
                                               String password, int maxDownloads) {
        sharesLock.lock();
        try {
        ShareEntry e = new ShareEntry();
        e.token = UUID.randomUUID().toString().replace("-", "");
        e.filePath = filePath;
        e.createdAt = System.currentTimeMillis();
        e.expiresAt = (expiryHours > 0) ? e.createdAt + expiryHours * 3_600_000L : 0L;
        e.passwordHash = (password != null && !password.isEmpty()) ? sha256(password) : null;
        e.maxDownloads = Math.max(0, maxDownloads);
        e.downloadCount = 0;
        shares.put(e.token, e);
        save();
        return e;
        } finally {
            sharesLock.unlock();
        }
    }

    /** Returns the entry for {@code token}, or {@code null} if unknown. */
    public ShareEntry getShare(String token) {
        sharesLock.lock();
        try {
        return token == null ? null : shares.get(token);
        } finally {
            sharesLock.unlock();
        }
    }

    /**
     * Returns {@code true} when the share is valid: exists, not expired, download
     * cap not exceeded, and password (if any) matches.
     */
    public boolean isValid(String token, String candidatePassword) {
        sharesLock.lock();
        try {
        ShareEntry e = getShare(token);
        if (e == null) return false;
        if (isExpired(e)) return false;
        if (e.maxDownloads > 0 && e.downloadCount >= e.maxDownloads) return false;
        if (e.passwordHash != null) {
            if (candidatePassword == null || candidatePassword.isEmpty()) return false;
            if (!e.passwordHash.equals(sha256(candidatePassword))) return false;
        }
        return true;
        } finally {
            sharesLock.unlock();
        }
    }

    /** Returns {@code true} if the share requires a password. */
    public boolean requiresPassword(String token) {
        sharesLock.lock();
        try {
        ShareEntry e = getShare(token);
        return e != null && e.passwordHash != null;
        } finally {
            sharesLock.unlock();
        }
    }

    /** Records one download hit and saves. */
    public void recordDownload(String token) {
        sharesLock.lock();
        try {
        ShareEntry e = shares.get(token);
        if (e != null) {
            e.downloadCount++;
            save();
        }
        } finally {
            sharesLock.unlock();
        }
    }

    /** Removes the share. */
    public void revokeShare(String token) {
        sharesLock.lock();
        try {
        if (shares.remove(token) != null) save();
        } finally {
            sharesLock.unlock();
        }
    }

    /** Returns a snapshot list of all shares (copies). */
    public List<ShareEntry> listShares() {
        sharesLock.lock();
        try {
        return new ArrayList<>(shares.values());
        } finally {
            sharesLock.unlock();
        }
    }

    /** Purges expired entries and saves if anything was removed. */
    public void cleanExpired() {
        sharesLock.lock();
        try {
        boolean changed = shares.values().removeIf(this::isExpired);
        if (changed) save();
        } finally {
            sharesLock.unlock();
        }
    }

    // ---- Helpers ------------------------------------------------------------

    public boolean isExpired(ShareEntry e) {
        return e.expiresAt > 0 && System.currentTimeMillis() > e.expiresAt;
    }

    // ---- Persistence --------------------------------------------------------

    private void load() {
        if (!Files.exists(sharesFile)) return;
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(sharesFile)) {
            p.load(in);
        } catch (IOException ex) {
            log.warn("Could not load shares.properties: {}", ex.getMessage());
            return;
        }
        // collect all known tokens
        Set<String> tokens = new LinkedHashSet<>();
        for (String key : p.stringPropertyNames()) {
            int dot = key.indexOf('.');
            if (dot > 0) tokens.add(key.substring(0, dot));
        }
        for (String token : tokens) {
            ShareEntry e = new ShareEntry();
            e.token = token;
            e.filePath = p.getProperty(token + ".path", "");
            e.expiresAt = parseLong(p.getProperty(token + ".expires", "0"));
            e.passwordHash = p.getProperty(token + ".passwordHash", null);
            if ("".equals(e.passwordHash)) e.passwordHash = null;
            e.createdAt = parseLong(p.getProperty(token + ".created", "0"));
            e.downloadCount = (int) parseLong(p.getProperty(token + ".downloads", "0"));
            e.maxDownloads = (int) parseLong(p.getProperty(token + ".maxDownloads", "0"));
            shares.put(token, e);
        }
    }

    private void save() {
        try {
            Files.createDirectories(sharesFile.getParent());
        } catch (IOException ignore) {}

        Properties p = new Properties();
        for (ShareEntry e : shares.values()) {
            p.setProperty(e.token + ".path", e.filePath);
            p.setProperty(e.token + ".expires", String.valueOf(e.expiresAt));
            p.setProperty(e.token + ".passwordHash", e.passwordHash == null ? "" : e.passwordHash);
            p.setProperty(e.token + ".created", String.valueOf(e.createdAt));
            p.setProperty(e.token + ".downloads", String.valueOf(e.downloadCount));
            p.setProperty(e.token + ".maxDownloads", String.valueOf(e.maxDownloads));
        }
        try (OutputStream out = Files.newOutputStream(sharesFile,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            p.store(out, "DocFinder share tokens");
        } catch (IOException ex) {
            log.warn("Could not save shares.properties: {}", ex.getMessage());
        }
    }

    // ---- Utilities ----------------------------------------------------------

    static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 unavailable", e);
        }
    }

    private static long parseLong(String s) {
        if (s == null) return 0L;
        try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return 0L; }
    }
}
