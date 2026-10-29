package com.project.migration.application.agent;

import com.project.migration.domain.model.Checks;
import com.project.migration.domain.model.MigrationStage;
import com.project.migration.domain.model.llm.LlmTier;

/**
 * One tool-calling job for the model.
 *
 * @param purpose label for telemetry and logs, e.g. {@code navigate-android:CardsTest#openCardDetails}
 */
public record AgentTask(
        LlmTier tier, MigrationStage stage, String purpose, String systemPrompt, String userPrompt,
        boolean reasoning) {

    public AgentTask {
        Checks.notNull(tier, "tier");
        Checks.notNull(stage, "stage");
        Checks.notBlank(purpose, "purpose");
        Checks.notBlank(systemPrompt, "systemPrompt");
        Checks.notBlank(userPrompt, "userPrompt");
    }
}
