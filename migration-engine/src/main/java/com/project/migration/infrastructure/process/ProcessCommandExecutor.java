package com.project.migration.infrastructure.process;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * {@link CommandExecutor} on {@link ProcessBuilder}. Output is read concurrently so a chatty build cannot block
 * on a full pipe; only the tail is kept.
 */
public final class ProcessCommandExecutor implements CommandExecutor {

    static final int MAX_OUTPUT_CHARS = 200_000;

    @Override
    public CommandResult run(List<String> command, Path workDir, Duration timeout) {
        long started = System.nanoTime();
        Process process;
        try {
            process = new ProcessBuilder(command).directory(workDir.toFile()).redirectErrorStream(true).start();
        } catch (IOException e) {
            return new CommandResult(-1, "cannot start " + command.getFirst() + ": " + e.getMessage(), false,
                    Duration.ofNanos(System.nanoTime() - started));
        }
        StringBuilder output = new StringBuilder();
        Thread reader = Thread.ofVirtual().start(() -> drain(process.getInputStream(), output));
        boolean finished;
        try {
            finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            kill(process);
            return new CommandResult(-1, "interrupted", false, Duration.ofNanos(System.nanoTime() - started));
        }
        if (!finished) {
            kill(process);
        }
        try {
            reader.join(Duration.ofSeconds(5));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        synchronized (output) {
            return new CommandResult(finished ? process.exitValue() : -1, output.toString(), !finished,
                    Duration.ofNanos(System.nanoTime() - started));
        }
    }

    private static void kill(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private static void drain(InputStream stream, StringBuilder output) {
        byte[] buffer = new byte[8192];
        try (stream) {
            int read;
            while ((read = stream.read(buffer)) > 0) {
                String chunk = new String(buffer, 0, read, StandardCharsets.UTF_8);
                synchronized (output) {
                    output.append(chunk);
                    if (output.length() > MAX_OUTPUT_CHARS) {
                        output.delete(0, output.length() - MAX_OUTPUT_CHARS);
                    }
                }
            }
        } catch (IOException ignored) {
            // the process was killed; whatever was read is all there is
        }
    }
}
