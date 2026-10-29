package com.project.migration.testsupport;

import com.project.migration.domain.model.MigrationStage;
import com.project.migration.domain.model.code.TestMethodRef;
import com.project.migration.domain.model.llm.LlmTier;
import com.project.migration.domain.port.MigrationLoggerPort;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps every logged line for assertions.
 */
public final class RecordingLogger implements MigrationLoggerPort {

    public final List<String> lines = new ArrayList<>();
    public final List<String> reasoning = new ArrayList<>();

    @Override
    public void startSession(String testClassFqcn) {
    }

    @Override
    public void enterStage(MigrationStage stage) {
    }

    @Override
    public void enterTest(TestMethodRef test) {
    }

    @Override
    public void exitTest() {
    }

    @Override
    public void info(String message) {
        lines.add("INFO " + message);
    }

    @Override
    public void warn(String message) {
        lines.add("WARN " + message);
    }

    @Override
    public void error(String message, Throwable cause) {
        lines.add("ERROR " + message);
    }

    @Override
    public void reasoning(LlmTier tier, String purpose, String reasoningText) {
        reasoning.add(tier + "/" + purpose + ": " + reasoningText);
    }

    @Override
    public void endSession() {
    }

    public boolean contains(String fragment) {
        return lines.stream().anyMatch(line -> line.contains(fragment));
    }
}
