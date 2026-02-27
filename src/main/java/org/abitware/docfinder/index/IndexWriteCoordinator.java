package org.abitware.docfinder.index;

import java.util.concurrent.Callable;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Serializes index write operations across live watch, network polling,
 * and manual full indexing to avoid Lucene lock contention.
 */
public final class IndexWriteCoordinator {
    private static final ReentrantLock WRITE_LOCK = new ReentrantLock(true);

    private IndexWriteCoordinator() {
    }

    public static void run(Runnable runnable) {
        WRITE_LOCK.lock();
        try {
            runnable.run();
        } finally {
            WRITE_LOCK.unlock();
        }
    }

    public static <T> T call(Callable<T> callable) throws Exception {
        WRITE_LOCK.lock();
        try {
            return callable.call();
        } finally {
            WRITE_LOCK.unlock();
        }
    }
}
