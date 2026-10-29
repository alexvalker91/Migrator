package com.project.migration.infrastructure.fs;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Short exclusive critical sections over a directory shared by several engine processes (e.g. two classes
 * migrated in parallel on one {@code .migration/}). Combines an in-process lock (OS file locks are per process)
 * with an OS lock on {@code <dir>/.lock}, released by the OS even if the JVM dies.
 */
public final class DirectoryLock {

    static final String LOCK_FILE = ".lock";

    private static final ConcurrentHashMap<Path, ReentrantLock> IN_PROCESS = new ConcurrentHashMap<>();

    private final Path lockFile;
    private final ReentrantLock inProcess;

    public DirectoryLock(Path directory) {
        this.lockFile = directory.toAbsolutePath().normalize().resolve(LOCK_FILE);
        this.inProcess = IN_PROCESS.computeIfAbsent(lockFile, path -> new ReentrantLock());
    }

    /** Runs {@code action} holding the lock. Re-entrant within a thread. */
    public <T> T withLock(Supplier<T> action) {
        inProcess.lock();
        try {
            if (inProcess.getHoldCount() > 1) {
                return action.get();
            }
            Files.createDirectories(lockFile.getParent());
            try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                return action.get();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot lock " + lockFile, e);
        } finally {
            inProcess.unlock();
        }
    }

    public void withLock(Runnable action) {
        withLock(() -> {
            action.run();
            return null;
        });
    }
}
