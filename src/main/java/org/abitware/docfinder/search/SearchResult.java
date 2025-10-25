package org.abitware.docfinder.search;

/** 搜索结果模型（保持最小字段） */
public class SearchResult {
    public final String name;
    public final String path;
    public final float score;
    public final long ctime;
    public final long atime;
    public final String match;
    public final long sizeBytes;
    public final boolean directory;

    public SearchResult(String name, String path, float score) {
        this(name, path, score, 0L, 0L, "", 0L, false);
    }

    public SearchResult(String name, String path, float score,
                        long ctime, long atime, String match) {
        this(name, path, score, ctime, atime, match, 0L, false);
    }

    public SearchResult(String name, String path, float score,
                        long ctime, long atime, String match, long sizeBytes) {
        this(name, path, score, ctime, atime, match, sizeBytes, false);
    }

    public SearchResult(String name, String path, float score,
                        long ctime, long atime, String match, long sizeBytes, boolean directory) {
        this.name = name;
        this.path = path;
        this.score = score;
        this.ctime = ctime;
        this.atime = atime;
        this.match = match;
        this.sizeBytes = sizeBytes;
        this.directory = directory;
    }
}
