package com.project.migration.infrastructure.process;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Runs an external command to completion. The seam between the Maven adapter and the operating system.
 */
public interface CommandExecutor {

    /**
     * @param command the full command line
     * @param timeout the process (and its children) is killed after this long
     * @return the outcome; a start failure or a timeout is a result, not an exception
     */
    CommandResult run(List<String> command, Path workDir, Duration timeout);

    /**
     * @param exitCode  process exit code; {@code -1} when it never started or was killed
     * @param output    stdout and stderr interleaved, the last part when it was very long
     * @param timedOut  the timeout elapsed and the process was killed
     */
    record CommandResult(int exitCode, String output, boolean timedOut, Duration duration) {
        public boolean succeeded() {
            return exitCode == 0 && !timedOut;
        }
    }
}
