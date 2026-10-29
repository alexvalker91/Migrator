package com.project.migration.infrastructure.process;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisabledOnOs(OS.WINDOWS)
class ProcessCommandExecutorTest {

    @TempDir
    Path dir;

    private final ProcessCommandExecutor executor = new ProcessCommandExecutor();

    @Test
    void capturesOutputOfBothStreamsAndTheExitCode() {
        CommandExecutor.CommandResult result = executor.run(
                List.of("sh", "-c", "echo out; echo err 1>&2; exit 3"), dir, Duration.ofSeconds(10));

        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.output()).contains("out").contains("err");
        assertThat(result.timedOut()).isFalse();
        assertThat(result.succeeded()).isFalse();
    }

    @Test
    void runsInTheGivenDirectory() {
        CommandExecutor.CommandResult result = executor.run(List.of("pwd"), dir, Duration.ofSeconds(10));

        assertThat(result.succeeded()).isTrue();
        assertThat(result.output().strip()).endsWith(dir.getFileName().toString());
    }

    @Test
    void killsAProcessThatOutlivesTheTimeoutIncludingItsChildren() {
        CommandExecutor.CommandResult result = executor.run(
                List.of("sh", "-c", "sleep 30 & sleep 30"), dir, Duration.ofMillis(500));

        assertThat(result.timedOut()).isTrue();
        assertThat(result.exitCode()).isEqualTo(-1);
        assertThat(result.duration()).isLessThan(Duration.ofSeconds(10));
    }

    @Test
    void aMissingExecutableIsAResultNotAnException() {
        CommandExecutor.CommandResult result = executor.run(List.of("definitely-not-a-command"), dir, Duration.ofSeconds(5));

        assertThat(result.exitCode()).isEqualTo(-1);
        assertThat(result.output()).contains("cannot start");
    }

    @Test
    void keepsOnlyTheTailOfVeryLongOutput() {
        CommandExecutor.CommandResult result = executor.run(
                List.of("sh", "-c", "yes line | head -c 400000; echo THE-END"), dir, Duration.ofSeconds(20));

        assertThat(result.output().length()).isLessThanOrEqualTo(ProcessCommandExecutor.MAX_OUTPUT_CHARS + 8192);
        assertThat(result.output()).contains("THE-END");
    }
}
