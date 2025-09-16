package org.abitware.docfinder.search;

/** 搜索结果模型（先最小字段） */
public class SearchResult {
    public final String name;
    public final String path;
    public final float score;
    public final long ctime;
    public final long atime;
    public final String match;
    public final long sizeBytes; // ✅ 新增：文件大小（字节）

    // 兼容旧三参
    public SearchResult(String name, String path, float score) {
        this(name, path, score, 0L, 0L, "", 0L);
    }
    // 兼容第8步的六参
    public SearchResult(String name, String path, float score,
                        long ctime, long atime, String match) {
        this(name, path, score, ctime, atime, match, 0L);
    }
    // ✅ 新版全参
    public SearchResult(String name, String path, float score,
                        long ctime, long atime, String match, long sizeBytes) {
        this.name = name; this.path = path; this.score = score;
        this.ctime = ctime; this.atime = atime; this.match = match;
        this.sizeBytes = sizeBytes;
    }
}
