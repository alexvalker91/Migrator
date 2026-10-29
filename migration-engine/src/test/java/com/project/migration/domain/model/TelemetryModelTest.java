package com.project.migration.domain.model;

import com.project.migration.domain.model.llm.LlmTier;
import com.project.migration.domain.model.telemetry.CacheStatistics;
import com.project.migration.domain.model.telemetry.LlmCallRecord;
import com.project.migration.domain.model.telemetry.ModelPricing;
import com.project.migration.domain.model.telemetry.SavingsSource;
import com.project.migration.domain.model.telemetry.TelemetrySnapshot;
import com.project.migration.domain.model.telemetry.TokenSavings;
import com.project.migration.domain.model.telemetry.TokenUsage;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TelemetryModelTest {

    @Test
    void tokenUsageAddsAllCounters() {
        TokenUsage sum = new TokenUsage(100, 20, 5, 40).plus(new TokenUsage(10, 2, 1, 0));

        assertThat(sum).isEqualTo(new TokenUsage(110, 22, 6, 40));
        assertThat(sum.total()).isEqualTo(138);
    }

    @Test
    void tokenUsageRejectsInvalidCounters() {
        assertThatThrownBy(() -> new TokenUsage(-1, 0, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TokenUsage(10, 0, 0, 11))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cachedInput");
    }

    @Test
    void pricingBillsCachedInputInsteadOfRegularInput() {
        ModelPricing pricing = new ModelPricing("gpt-4o",
                new BigDecimal("2.50"), new BigDecimal("10.00"), new BigDecimal("10.00"), new BigDecimal("1.25"));

        BigDecimal cost = pricing.costOf(new TokenUsage(1_000_000, 100_000, 50_000, 400_000));

        // 600k * 2.50 + 400k * 1.25 + 100k * 10 + 50k * 10 = 1.5 + 0.5 + 1.0 + 0.5
        assertThat(cost).isEqualByComparingTo("3.5");
        assertThat(pricing.inputCostOf(200_000)).isEqualByComparingTo("0.5");
    }

    @Test
    void snapshotAggregatesUsageCostAndSavings() {
        LlmCallRecord tier1 = call(LlmTier.TIER_1, new TokenUsage(100, 10, 5, 0));
        LlmCallRecord tier2a = call(LlmTier.TIER_2, new TokenUsage(50, 5, 0, 0));
        LlmCallRecord tier2b = call(LlmTier.TIER_2, new TokenUsage(30, 3, 0, 10));
        TelemetrySnapshot snapshot = new TelemetrySnapshot(
                List.of(tier1, tier2a, tier2b),
                List.of(new TokenSavings(SavingsSource.XML_CLEANING, 9_000),
                        new TokenSavings(SavingsSource.XML_CLEANING, 1_000),
                        new TokenSavings(SavingsSource.SNAPSHOT_CACHE, 500)),
                new CacheStatistics(3, 1),
                Map.of(LlmTier.TIER_1, new BigDecimal("0.30"), LlmTier.TIER_2, new BigDecimal("0.05")),
                new BigDecimal("0.02"));

        assertThat(snapshot.usageByTier()).containsEntry(LlmTier.TIER_2, new TokenUsage(80, 8, 0, 10));
        assertThat(snapshot.totalUsage()).isEqualTo(new TokenUsage(180, 18, 5, 10));
        assertThat(snapshot.totalCost()).isEqualByComparingTo("0.35");
        assertThat(snapshot.savedTokens(SavingsSource.XML_CLEANING)).isEqualTo(10_000);
        assertThat(snapshot.totalSavedTokens()).isEqualTo(10_500);
        assertThat(snapshot.cacheStatistics().hitRate()).isEqualTo(0.75);
    }

    @Test
    void emptyCacheStatisticsHaveZeroHitRate() {
        assertThat(CacheStatistics.EMPTY.hitRate()).isZero();
    }

    private static LlmCallRecord call(LlmTier tier, TokenUsage usage) {
        return new LlmCallRecord(Instant.EPOCH, MigrationStage.STAGE_3_CODEGEN, tier, "model-" + tier,
                "test", usage, Duration.ofMillis(10), Optional.empty());
    }
}
