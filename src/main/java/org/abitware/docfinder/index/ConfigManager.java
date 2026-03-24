package org.abitware.docfinder.index;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

import org.abitware.docfinder.util.AppPaths;

public class ConfigManager {
	private final Path dir = AppPaths.getBaseDir();
	private final Path file = dir.resolve("config.properties");

	public IndexSettings loadIndexSettings() {
		IndexSettings s = new IndexSettings();
		Properties p = new Properties();
		try (InputStream in = Files.exists(file) ? Files.newInputStream(file) : null) {
			if (in != null)
				p.load(in);
		} catch (IOException ignore) {
		}
		s.maxFileMB = Long.parseLong(p.getProperty("index.maxFileMB", String.valueOf(s.maxFileMB)));
		s.parseTimeoutSec = Integer.parseInt(p.getProperty("index.parseTimeoutSec", String.valueOf(s.parseTimeoutSec)));
		s.includeExt = split(p.getProperty("index.includeExt", join(s.includeExt)));
		s.excludeGlob = splitSemi(p.getProperty("index.excludeGlob", joinSemi(s.excludeGlob)));
		
		s.parseTextLike = Boolean.parseBoolean(p.getProperty("index.parseTextLike", String.valueOf(s.parseTextLike)));
		s.textMaxBytes = Long.parseLong(p.getProperty("index.textMaxBytes", String.valueOf(s.textMaxBytes)));
		s.textExts = java.util.Arrays.asList(p.getProperty("index.textExts", String.join(",", s.textExts)).split(","));
		s.previewTimeoutSec = Integer.parseInt(p.getProperty("index.previewTimeoutSec", String.valueOf(s.previewTimeoutSec)));
		s.maxExtractChars = Integer.parseInt(p.getProperty("index.maxExtractChars", String.valueOf(s.maxExtractChars)));
		s.nrtCacheMaxMB = Integer.parseInt(p.getProperty("index.nrtCacheMaxMB", String.valueOf(s.nrtCacheMaxMB)));
		
		return s;
	}

	public void saveIndexSettings(IndexSettings s) {
		try {
			Files.createDirectories(dir);
		} catch (IOException ignore) 
		{
		}
		Properties p = new Properties();
		p.setProperty("index.maxFileMB", String.valueOf(s.maxFileMB));
		p.setProperty("index.parseTimeoutSec", String.valueOf(s.parseTimeoutSec));
		p.setProperty("index.includeExt", join(s.includeExt));
		p.setProperty("index.excludeGlob", joinSemi(s.excludeGlob));
		
		p.setProperty("index.parseTextLike", String.valueOf(s.parseTextLike));
		p.setProperty("index.textMaxBytes", String.valueOf(s.textMaxBytes));
		p.setProperty("index.textExts", String.join(",", s.textExts));
		p.setProperty("index.previewTimeoutSec", String.valueOf(s.previewTimeoutSec));
		p.setProperty("index.maxExtractChars", String.valueOf(s.maxExtractChars));
		p.setProperty("index.nrtCacheMaxMB", String.valueOf(s.nrtCacheMaxMB));
		
		try (OutputStream out = Files.newOutputStream(file, StandardOpenOption.CREATE,
				StandardOpenOption.TRUNCATE_EXISTING)) {
			p.store(out, "DocFinder settings");
		} catch (IOException ignore) {
		}
	}

	// --- Open With 映射（openwith.<ext> -> program absolute path） ---
	public String getOpenWithProgram(String ext) {
	    java.util.Properties p = loadAll();
	    return p.getProperty("openwith." + ext.toLowerCase(), null);
	}
	public void setOpenWithProgram(String ext, String programAbsPath) {
	    java.util.Properties p = loadAll();
	    if (programAbsPath == null || programAbsPath.trim().isEmpty()) {
	        p.remove("openwith." + ext.toLowerCase());
	    } else {
	        p.setProperty("openwith." + ext.toLowerCase(), programAbsPath);
	    }
	    saveAll(p);
	}

	// 可复用的小工具：读/写完整 properties
	private java.util.Properties loadAll() {
	    java.util.Properties p = new java.util.Properties();
	    try {
	        if (java.nio.file.Files.exists(file)) {
	            try (java.io.InputStream in = java.nio.file.Files.newInputStream(file)) {
	                p.load(in);
	            }
	        }
	    } catch (java.io.IOException ignore) {}
	    return p;
	}
	private void saveAll(java.util.Properties p) {
	    try {
	        java.nio.file.Files.createDirectories(file.getParent());
	        try (java.io.OutputStream out = java.nio.file.Files.newOutputStream(
	                file, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {
	            p.store(out, "DocFinder settings");
	        }
	    } catch (java.io.IOException ignore) {}
	}

	
	private static List<String> split(String s) {
		return Arrays.stream(s.split(",")).map(String::trim).filter(x -> !x.isEmpty()).collect(Collectors.toList());
	}

	private static String join(List<String> l) {
		return String.join(",", l);
	}

	private static List<String> splitSemi(String s) {
		return Arrays.stream(s.split(";")).map(String::trim).filter(x -> !x.isEmpty()).collect(Collectors.toList());
	}

	private static String joinSemi(List<String> l) {
		return String.join(";", l);
	}
	
	// --- Web server settings ---
	public boolean isWebEnabled() {
	    return Boolean.parseBoolean(loadAll().getProperty("web.enabled", "false"));
	}
	public void setWebEnabled(boolean on) {
	    java.util.Properties p = loadAll();
	    p.setProperty("web.enabled", String.valueOf(on));
	    saveAll(p);
	}
	public int getWebPort() {
	    try { return Integer.parseInt(loadAll().getProperty("web.port", "7070")); }
	    catch (Exception e) { return 7070; }
	}
	public void setWebPort(int port) {
	    java.util.Properties p = loadAll();
	    p.setProperty("web.port", String.valueOf(Math.max(1024, Math.min(65535, port))));
	    saveAll(p);
	}
	public String getWebBindAddress() {
	    return loadAll().getProperty("web.bindAddress", "127.0.0.1");
	}
	public void setWebBindAddress(String addr) {
	    java.util.Properties p = loadAll();
	    p.setProperty("web.bindAddress", addr == null ? "127.0.0.1" : addr.trim());
	    saveAll(p);
	}

	
	public boolean isPollingEnabled() {
	    java.util.Properties p = loadAll();
	    return Boolean.parseBoolean(p.getProperty("poll.enabled", "false"));
	}
	public void setPollingEnabled(boolean on) {
	    java.util.Properties p = loadAll();
	    p.setProperty("poll.enabled", String.valueOf(on));
	    saveAll(p);
	}
	public int getPollingMinutes() {
	    java.util.Properties p = loadAll();
	    try { return Integer.parseInt(p.getProperty("poll.minutes", "10")); }
	    catch (Exception e) { return 10; }
	}
	public void setPollingMinutes(int minutes) {
	    java.util.Properties p = loadAll();
	    p.setProperty("poll.minutes", String.valueOf(Math.max(1, minutes)));
	    saveAll(p);
	}

	public Path getIndexDir() {
	    String saved = loadAll().getProperty("index.dir", "").trim();
	    if (!saved.isEmpty()) {
	        try {
	            return Paths.get(saved).toAbsolutePath().normalize();
	        } catch (Exception ignore) {
	        }
	    }
	    return dir.resolve("index").toAbsolutePath().normalize();
	}

	public void setIndexDir(Path path) {
	    java.util.Properties p = loadAll();
	    if (path == null) {
	        p.remove("index.dir");
	    } else {
	        p.setProperty("index.dir", path.toAbsolutePath().normalize().toString());
	    }
	    saveAll(p);
	}

	public boolean isShowScoreColumn() {
	    return Boolean.parseBoolean(loadAll().getProperty("ui.showScoreColumn", "false"));
	}

	public void setShowScoreColumn(boolean show) {
	    java.util.Properties p = loadAll();
	    p.setProperty("ui.showScoreColumn", String.valueOf(show));
	    saveAll(p);
	}

	
}
