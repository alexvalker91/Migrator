package com.project.migration.application.agent;

import com.project.migration.domain.model.Checks;
import com.project.migration.domain.model.MigrationStage;
import com.project.migration.domain.model.llm.LlmTier;
import com.project.migration.domain.model.media.ImageAttachment;

import java.util.List;

/**
 * One tool-calling job for the model.
 *
 * @param images  screenshots shown to the model with the first message (vision tiers only)
 * @param purpose label for telemetry and logs, e.g. {@code navigate-android:CardsTest#openCardDetails}
 */
public record AgentTask(
        LlmTier tier, MigrationStage stage, String purpose, String systemPrompt, String userPrompt,
        boolean reasoning, List<ImageAttachment> images) {

    public AgentTask(LlmTier tier, MigrationStage stage, String purpose, String systemPrompt, String userPrompt,
                     boolean reasoning) {
        this(tier, stage, purpose, systemPrompt, userPrompt, reasoning, List.of());
    }

    public AgentTask {
        Checks.notNull(tier, "tier");
        Checks.notNull(stage, "stage");
        Checks.notBlank(purpose, "purpose");
        Checks.notBlank(systemPrompt, "systemPrompt");
        Checks.notBlank(userPrompt, "userPrompt");
        images = List.copyOf(Checks.notNull(images, "images"));
    }
}
