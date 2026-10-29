package com.project.migration.domain.port;

import com.project.migration.domain.model.MigrationStage;
import com.project.migration.domain.model.code.TestMethodRef;
import com.project.migration.domain.model.llm.LlmTier;

/**
 * Structured session log of a class migration ({@code migration_reports/<TestClass>/execution.log} + console).
 * Lines are tagged with the current stage and test method. The pipeline is sequential per class, so the
 * context is session state rather than a parameter of every call.
 */
public interface MigrationLoggerPort {

    /** Opens the session log of {@code testClassFqcn}; ends any previous session. */
    void startSession(String testClassFqcn);

    void enterStage(MigrationStage stage);

    void enterTest(TestMethodRef test);

    void exitTest();

    void info(String message);

    void warn(String message);

    void error(String message, Throwable cause);

    /**
     * Model reasoning. Always written to the session log; printed to the console as a
     * {@code [🧠 LLM REASONING]} block only when {@code showReasoning} is enabled.
     */
    void reasoning(LlmTier tier, String purpose, String reasoningText);

    /** Flushes and closes the session log. Idempotent. */
    void endSession();
}
