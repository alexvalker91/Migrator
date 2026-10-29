package com.project.migration.infrastructure.logging;

import com.project.migration.domain.model.MigrationStage;
import com.project.migration.domain.model.code.TestMethodRef;
import com.project.migration.domain.model.llm.LlmTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LogbackMigrationLoggerTest {

    private static final String TEST_CLASS = "webdriverio.LoginTest";

    @TempDir
    Path reportsDir;

    @Test
    void writesTaggedLinesToSessionFileAndConsole() throws IOException {
        ByteArrayOutputStream console = new ByteArrayOutputStream();
        try (LogbackMigrationLogger logger = new LogbackMigrationLogger(reportsDir, false, console)) {
            logger.startSession(TEST_CLASS);
            logger.enterStage(MigrationStage.STAGE_4_STABILIZATION);
            logger.enterTest(new TestMethodRef(TEST_CLASS, "loginNegativeTest"));
            logger.info("run 1/3 PASSED");
            logger.exitTest();
            logger.warn("no reasoning deployment for TIER_2");
            logger.error("MCP session lost", new IllegalStateException("boom"));
        }

        String file = readLog();
        assertThat(file)
                .contains("INFO  [STAGE_4_STABILIZATION] [LoginTest#loginNegativeTest] run 1/3 PASSED")
                .contains("WARN  [STAGE_4_STABILIZATION] [-] no reasoning deployment for TIER_2")
                .contains("ERROR [STAGE_4_STABILIZATION] [-] MCP session lost")
                .contains("java.lang.IllegalStateException: boom")
                .contains("Migration session finished");
        assertThat(console.toString(StandardCharsets.UTF_8)).contains("run 1/3 PASSED");
    }

    @Test
    void reasoningGoesToFileAlwaysButToConsoleOnlyWhenEnabled() throws IOException {
        ByteArrayOutputStream quietConsole = new ByteArrayOutputStream();
        try (LogbackMigrationLogger logger = new LogbackMigrationLogger(reportsDir, false, quietConsole)) {
            logger.startSession(TEST_CLASS);
            logger.reasoning(LlmTier.TIER_1, "heal:loginNegativeTest", "Spinner was still visible.\nAdd wait.");
        }
        assertThat(readLog())
                .contains(LogbackMigrationLogger.REASONING_HEADER + " TIER_1 · heal:loginNegativeTest")
                .contains("│ Spinner was still visible.")
                .contains("│ Add wait.");
        assertThat(quietConsole.toString(StandardCharsets.UTF_8)).doesNotContain(LogbackMigrationLogger.REASONING_HEADER);

        ByteArrayOutputStream verboseConsole = new ByteArrayOutputStream();
        try (LogbackMigrationLogger logger = new LogbackMigrationLogger(reportsDir, true, verboseConsole)) {
            logger.startSession(TEST_CLASS);
            logger.reasoning(LlmTier.TIER_1, "heal:loginNegativeTest", "Second session reasoning");
        }
        assertThat(verboseConsole.toString(StandardCharsets.UTF_8))
                .contains(LogbackMigrationLogger.REASONING_HEADER)
                .contains("│ Second session reasoning");
        assertThat(readLog()).contains("Spinner was still visible.").contains("Second session reasoning");
    }

    @Test
    void blankReasoningIsIgnoredAndLoggingWithoutSessionStaysOnConsole() throws IOException {
        ByteArrayOutputStream console = new ByteArrayOutputStream();
        try (LogbackMigrationLogger logger = new LogbackMigrationLogger(reportsDir, true, console)) {
            logger.info("before session");
            logger.startSession(TEST_CLASS);
            logger.reasoning(LlmTier.TIER_2, "map", "   ");
            logger.endSession();
            logger.endSession();
        }

        assertThat(console.toString(StandardCharsets.UTF_8)).contains("before session");
        assertThat(readLog()).doesNotContain("before session").doesNotContain(LogbackMigrationLogger.REASONING_HEADER);
    }

    private String readLog() throws IOException {
        return Files.readString(reportsDir.resolve("LoginTest").resolve(LogbackMigrationLogger.LOG_FILE_NAME),
                StandardCharsets.UTF_8);
    }
}
