package org.abitware.docfinder.index;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexFormatTooOldException;
import org.apache.lucene.index.IndexNotFoundException;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.SegmentInfos;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.Lock;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Preserves incompatible indexes before any search, watcher or registry opens. */
public final class IndexFormatGuard {
    public enum State { READY, INDEX_REBUILD_REQUIRED }

    public record Result(State state, List<Path> backups) { }

    private IndexFormatGuard() { }

    public static Result prepare(Path indexDir, Path appDataDir) throws IOException {
        List<Path> backups = new ArrayList<>();
        for (Path path : List.of(indexDir, appDataDir.resolve("deleted-paths-index"))) {
            if (!Files.isDirectory(path)) {
                continue;
            }
            try (FSDirectory directory = FSDirectory.open(path)) {
                if (!isOld(directory)) {
                    continue;
                }
                // write.lock 会在正常关闭后保留；只有真实文件锁才能证明可以迁移。
                // 锁保持到 rename 完成，竞争写者不能在检测与备份之间打开旧目录。
                try (Lock lock = directory.obtainLock(IndexWriter.WRITE_LOCK_NAME)) {
                    lock.ensureValid();
                    // 等待锁期间旧 writer 可能已完成升级；在锁内复核，避免误备份新提交。
                    if (!isOld(directory)) continue;
                    Path marker = marker(indexDir);
                    Files.createDirectories(marker.getParent());
                    Files.writeString(marker, "Lucene 8 backup requires Full Rebuild\n");
                    Path backup = path.resolveSibling(path.getFileName() + ".lucene8.bak-"
                            + Instant.now().toEpochMilli() + "-" + UUID.randomUUID());
                    Files.move(path, backup);
                    backups.add(backup);
                }
            }
        }
        // 全新安装时 isReady 同样为 false；只有真实备份或已存在的重建标记才表示需要重建，
        // 否则首次启动会误弹"索引需要重建"对话框。
        boolean rebuildPending = !backups.isEmpty() || Files.exists(marker(indexDir));
        return new Result(rebuildPending ? State.INDEX_REBUILD_REQUIRED : State.READY,
                List.copyOf(backups));
    }

    private static boolean isOld(FSDirectory directory) throws IOException {
        try {
            return SegmentInfos.readLatestCommit(directory).getIndexCreatedVersionMajor() < 9;
        } catch (IndexFormatTooOldException e) {
            return true;
        } catch (IndexNotFoundException e) {
            return false;
        }
    }

    public static boolean isReady(Path indexDir) {
        if (!Files.isDirectory(indexDir) || Files.exists(marker(indexDir))) {
            return false;
        }
        // indexExists 只证明有 segments 文件，不能证明当前 Lucene 能读取它。
        try (FSDirectory directory = FSDirectory.open(indexDir);
                DirectoryReader reader = DirectoryReader.open(directory)) {
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    public static void rebuildCompleted(Path indexDir) throws IOException {
        Files.deleteIfExists(marker(indexDir));
    }

    private static Path marker(Path indexDir) {
        return indexDir.toAbsolutePath().resolveSibling(indexDir.getFileName() + ".rebuild-required");
    }
}
