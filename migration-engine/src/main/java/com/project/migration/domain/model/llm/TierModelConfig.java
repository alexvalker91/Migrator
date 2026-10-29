package com.project.migration.domain.model.llm;

import com.project.migration.domain.model.Checks;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/**
 * DIAL deployments serving a tier.
 *
 * @param reasoningModelId          {@code -reasoning}/{@code -with-thinking} deployment, if the tier has one
 * @param fallbackModelIds          alternatives to {@code modelId}, tried in order when it is unavailable
 * @param reasoningFallbackModelIds alternatives to {@code reasoningModelId}, tried in order when it is unavailable
 */
public record TierModelConfig(
        LlmTier tier,
        String modelId,
        Optional<String> reasoningModelId,
        boolean supportsVision,
        List<String> fallbackModelIds,
        List<String> reasoningFallbackModelIds) {

    public TierModelConfig {
        Checks.notNull(tier, "tier");
        Checks.notBlank(modelId, "modelId");
        reasoningModelId = reasoningModelId == null ? Optional.empty() : reasoningModelId.filter(id -> !id.isBlank());
        fallbackModelIds = List.copyOf(Checks.notNull(fallbackModelIds, "fallbackModelIds"));
        reasoningFallbackModelIds = List.copyOf(Checks.notNull(reasoningFallbackModelIds, "reasoningFallbackModelIds"));
        fallbackModelIds.forEach(id -> Checks.notBlank(id, "fallbackModelId"));
        reasoningFallbackModelIds.forEach(id -> Checks.notBlank(id, "reasoningFallbackModelId"));
        if (reasoningModelId.isEmpty() && !reasoningFallbackModelIds.isEmpty()) {
            throw new IllegalArgumentException("reasoning fallbacks of " + tier + " need a reasoningModelId");
        }
    }

    /** Tier without alternative deployments. */
    public TierModelConfig(LlmTier tier, String modelId, Optional<String> reasoningModelId, boolean supportsVision) {
        this(tier, modelId, reasoningModelId, supportsVision, List.of(), List.of());
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

    /**
     * Deployments to try, in order, when the previous one is unavailable. With reasoning requested: the reasoning
     * deployment and its alternatives first, then the base chain (an answer without reasoning beats no answer).
     */
    public List<String> deploymentChain(boolean reasoningRequested) {
        List<String> chain = new ArrayList<>();
        if (reasoningRequested && reasoningModelId.isPresent()) {
            chain.add(reasoningModelId.get());
            chain.addAll(reasoningFallbackModelIds);
        }
        chain.add(modelId);
        chain.addAll(fallbackModelIds);
        return List.copyOf(new LinkedHashSet<>(chain));
    }

    /** Every deployment of the tier (pricing is configured per tier and expanded to all of them). */
    public List<String> allModelIds() {
        return deploymentChain(true);
    }
}
