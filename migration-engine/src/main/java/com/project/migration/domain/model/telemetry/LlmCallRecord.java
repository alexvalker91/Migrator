package com.project.migration.domain.model.telemetry;

import com.project.migration.domain.model.Checks;
import com.project.migration.domain.model.MigrationStage;
import com.project.migration.domain.model.code.TestMethodRef;
import com.project.migration.domain.model.llm.LlmTier;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Telemetry entry for one LLM call.
 */
public record LlmCallRecord(
        Instant timestamp,
        MigrationStage stage,
        LlmTier tier,
        String modelId,
        String purpose,
        TokenUsage usage,
        Duration latency,
        Optional<TestMethodRef> testMethod) {

    public LlmCallRecord {
        Checks.notNull(timestamp, "timestamp");
        Checks.notNull(stage, "stage");
        Checks.notNull(tier, "tier");
        Checks.notBlank(modelId, "modelId");
        Checks.notBlank(purpose, "purpose");
        Checks.notNull(usage, "usage");
        Checks.notNull(latency, "latency");
        testMethod = testMethod == null ? Optional.empty() : testMethod;
    }
}
