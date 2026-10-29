package com.project.migration.domain.model.llm;

import com.project.migration.domain.model.Checks;

import java.util.Optional;

/**
 * DIAL deployments serving a tier.
 *
 * @param reasoningModelId {@code -reasoning}/{@code -with-thinking} deployment, if the tier has one
 */
public record TierModelConfig(LlmTier tier, String modelId, Optional<String> reasoningModelId, boolean supportsVision) {

    public TierModelConfig {
        Checks.notNull(tier, "tier");
        Checks.notBlank(modelId, "modelId");
        reasoningModelId = reasoningModelId == null ? Optional.empty() : reasoningModelId.filter(id -> !id.isBlank());
    }

    /**
     * Deployment for a request; falls back to the base model when reasoning was requested but the tier has no
     * reasoning deployment (callers should log that fallback).
     */
    public String resolveModelId(boolean reasoningRequested) {
        return reasoningRequested ? reasoningModelId.orElse(modelId) : modelId;
    }

    public boolean fallsBackFromReasoning(boolean reasoningRequested) {
        return reasoningRequested && reasoningModelId.isEmpty();
    }
}
