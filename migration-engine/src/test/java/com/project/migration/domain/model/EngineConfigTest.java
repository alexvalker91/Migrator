package com.project.migration.domain.model;

import com.project.migration.domain.model.config.EngineConfig;
import com.project.migration.domain.model.llm.LlmTier;
import com.project.migration.domain.model.llm.TierModelConfig;
import com.project.migration.domain.model.telemetry.ModelPricing;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EngineConfigTest {

    private static final Path ROOT = Path.of("/work/migrator");
    private static final TierModelConfig TIER_1 = new TierModelConfig(LlmTier.TIER_1, "t1", Optional.of("t1-reasoning"), true);
    private static final TierModelConfig TIER_2 = new TierModelConfig(LlmTier.TIER_2, "t2", Optional.empty(), true);
    private static final TierModelConfig TIER_3 = new TierModelConfig(LlmTier.TIER_3, "t3", Optional.empty(), false);

    @Test
    void defaultsUseStandardLayoutAndGate() {
        ModelPricing pricing = new ModelPricing("t1", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE);
        EngineConfig config = EngineConfig.withDefaults(ROOT, true, List.of(TIER_1, TIER_2, TIER_3), List.of(pricing));

        assertThat(config.migrationDir()).isEqualTo(ROOT.resolve(".migration"));
        assertThat(config.reportDirFor("LoginTest")).isEqualTo(ROOT.resolve("migration_reports/LoginTest"));
        assertThat(config.stabilityRuns()).isEqualTo(3);
        assertThat(config.maxHealingAttempts()).isEqualTo(3);
        assertThat(config.modelFor(LlmTier.TIER_2)).isEqualTo(TIER_2);
        assertThat(config.pricingFor("t1")).contains(pricing);
        assertThat(config.pricingFor("unknown")).isEmpty();
    }

    @Test
    void everyTierMustBeConfiguredExactlyOnce() {
        assertThatThrownBy(() -> EngineConfig.withDefaults(ROOT, false, List.of(TIER_1, TIER_2), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TIER_3");
        assertThatThrownBy(() -> EngineConfig.withDefaults(ROOT, false, List.of(TIER_1, TIER_1, TIER_2, TIER_3), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("twice");
    }

    @Test
    void tier1MustSupportVision() {
        TierModelConfig blindTier1 = new TierModelConfig(LlmTier.TIER_1, "t1", Optional.empty(), false);

        assertThatThrownBy(() -> EngineConfig.withDefaults(ROOT, false, List.of(blindTier1, TIER_2, TIER_3), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("vision");
    }
}
