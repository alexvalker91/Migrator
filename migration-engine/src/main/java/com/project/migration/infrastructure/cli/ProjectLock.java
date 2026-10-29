package com.project.migration.infrastructure.cli;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * One engine process per project: two of them would share the Git working tree and {@code .migration/}, and one's
 * rollback would erase the other's edits. The OS drops the lock when the process dies, however it dies.
 */
final class ProjectLock implements AutoCloseable {

    static final String FILE_NAME = "engine.lock";

    private final FileChannel channel;
    private final FileLock lock;

    private ProjectLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    /**
     * @param owner what is written into the lock file for the next process that is refused (e.g. the classes)
     * @throws UsageException if another engine process holds the project
     */
    static ProjectLock acquire(Path migrationDir, String owner) {
        Path file = migrationDir.resolve(FILE_NAME);
        try {
            Files.createDirectories(migrationDir);
            FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException inThisProcess) {
                channel.close();
                throw new UsageException("another migration is already running in this process (" + file + ")");
            }
            if (lock == null) {
                String holder = read(channel);
                channel.close();
                throw new UsageException("another migration engine is running on this project: "
                        + (holder.isBlank() ? "(unknown)" : holder.strip().replace('\n', ' ')) + " (lock " + file + ")");
            }
            channel.truncate(0);
            channel.write(ByteBuffer.wrap(("pid=" + ProcessHandle.current().pid() + " classes=" + owner + "\n")
                    .getBytes(StandardCharsets.UTF_8)), 0);
            channel.force(true);
            return new ProjectLock(channel, lock);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot lock the project via " + file, e);
        }
    }

    private static String read(FileChannel channel) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(512);
        int read = channel.read(buffer, 0);
        return read <= 0 ? "" : new String(buffer.array(), 0, read, StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        try {
            lock.release();
            channel.close();
        } catch (IOException ignored) {
            // the OS releases the lock when the process ends anyway
        }
    }
}
