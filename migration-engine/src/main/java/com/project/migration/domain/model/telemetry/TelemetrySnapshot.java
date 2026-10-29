package com.project.migration.domain.model.telemetry;

import com.project.migration.domain.model.Checks;
import com.project.migration.domain.model.llm.LlmTier;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregated telemetry of a class migration, with costs already priced by the telemetry collector.
 */
public record TelemetrySnapshot(
        List<LlmCallRecord> llmCalls,
        List<TokenSavings> savings,
        CacheStatistics cacheStatistics,
        Map<LlmTier, BigDecimal> costByTier,
        BigDecimal estimatedSavedCost) {

    public TelemetrySnapshot {
        llmCalls = List.copyOf(Checks.notNull(llmCalls, "llmCalls"));
        savings = List.copyOf(Checks.notNull(savings, "savings"));
        Checks.notNull(cacheStatistics, "cacheStatistics");
        costByTier = Map.copyOf(Checks.notNull(costByTier, "costByTier"));
        Checks.notNull(estimatedSavedCost, "estimatedSavedCost");
    }

    public static TelemetrySnapshot empty() {
        return new TelemetrySnapshot(List.of(), List.of(), CacheStatistics.EMPTY, Map.of(), BigDecimal.ZERO);
    }

    public Map<LlmTier, TokenUsage> usageByTier() {
        Map<LlmTier, TokenUsage> result = new EnumMap<>(LlmTier.class);
        for (LlmCallRecord call : llmCalls) {
            result.merge(call.tier(), call.usage(), TokenUsage::plus);
        }
        return Map.copyOf(result);
    }

    public TokenUsage totalUsage() {
        return llmCalls.stream().map(LlmCallRecord::usage).reduce(TokenUsage.ZERO, TokenUsage::plus);
    }

    public BigDecimal totalCost() {
        return costByTier.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public long savedTokens(SavingsSource source) {
        return savings.stream().filter(s -> s.source() == source).mapToLong(TokenSavings::tokens).sum();
    }

    public long totalSavedTokens() {
        return savings.stream().mapToLong(TokenSavings::tokens).sum();
    }
}
