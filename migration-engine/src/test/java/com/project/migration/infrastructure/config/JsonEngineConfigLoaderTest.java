package com.project.migration.infrastructure.config;

import com.project.migration.domain.model.config.EngineConfig;
import com.project.migration.domain.model.llm.LlmTier;
import com.project.migration.domain.model.llm.TierModelConfig;
import com.project.migration.domain.model.telemetry.ModelPricing;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonEngineConfigLoaderTest {

    @TempDir
    Path dir;

    private final JsonEngineConfigLoader loader = new JsonEngineConfigLoader();

    @Test
    void defaultsDescribeTheDialDeploymentsOfEveryTier() {
        EngineConfig config = loader.load(dir, true, Optional.empty());

        TierModelConfig tier1 = config.modelFor(LlmTier.TIER_1);
        assertThat(tier1.deploymentChain(true)).containsExactly("gpt-6-astra-2026-09-03-reasoning",
                "gpt-5.6-sol-2026-07-09-reasoning", "claude-opus-5-5", "gpt-6.1-sol-2026-09-29", "claude-opus-5@default");
        assertThat(config.modelFor(LlmTier.TIER_2).modelId()).isEqualTo("claude-sonnet-5-5");
        assertThat(config.modelFor(LlmTier.TIER_3).reasoningModelId()).contains("gemini-3.8-flash-with-thinking");
        assertThat(config.showReasoning()).isTrue();
        assertThat(config.stabilityRuns()).isEqualTo(3);
        assertThat(config.migrationDir()).isEqualTo(dir.toAbsolutePath().normalize().resolve(".migration"));
    }

    @Test
    void tierPricingAppliesToEveryDeploymentOfTheTier() {
        EngineConfig config = loader.load(dir, false, Optional.empty());

        assertThat(config.pricingFor("gpt-5.6-sol-2026-07-09-reasoning")).map(ModelPricing::inputPer1M)
                .hasValueSatisfying(price -> assertThat(price).isEqualByComparingTo("5.00"));
        assertThat(config.pricingFor("gpt-5.4-nano-2026-03-17")).map(ModelPricing::outputPer1M)
                .hasValueSatisfying(price -> assertThat(price).isEqualByComparingTo("1.25"));
        assertThat(config.pricingFor("claude-sonnet-5-5")).map(ModelPricing::cachedInputPer1M)
                .hasValueSatisfying(price -> assertThat(price).isEqualByComparingTo("0.30"));
    }

    @Test
    void overrideFileIsDeepMergedAndCanPriceSingleDeployments() throws IOException {
        Path override = Files.writeString(dir.resolve("engine.json"), """
                {
                  "stability_runs": 5,
                  "tiers": {"TIER_2": {"model_id": "gpt-5.3-codex-2026-02-24", "fallback_model_ids": []}},
                  "model_pricing": {"gpt-6-astra-2026-09-03-reasoning":
                      {"input_per_1m": 10, "output_per_1m": 40, "reasoning_per_1m": 40, "cached_input_per_1m": 1}}
                }
                """);

        EngineConfig config = loader.load(dir, false, Optional.of(override));

        assertThat(config.stabilityRuns()).isEqualTo(5);
        assertThat(config.modelFor(LlmTier.TIER_2).deploymentChain(false)).containsExactly("gpt-5.3-codex-2026-02-24");
        assertThat(config.modelFor(LlmTier.TIER_2).reasoningModelId()).contains("gpt-5.3-codex-2026-02-24-reasoning");
        assertThat(config.pricingFor("gpt-6-astra-2026-09-03-reasoning")).map(ModelPricing::inputPer1M)
                .hasValueSatisfying(price -> assertThat(price).isEqualByComparingTo("10"));
        assertThat(config.pricingFor("claude-opus-5-5")).map(ModelPricing::inputPer1M)
                .hasValueSatisfying(price -> assertThat(price).isEqualByComparingTo("5.00"));
    }

    @Test
    void invalidOverridesFailWithTheSource() throws IOException {
        Path unknownKey = Files.writeString(dir.resolve("typo.json"), "{\"stabilty_runs\": 5}");
        Path missingTierModel = Files.writeString(dir.resolve("blank.json"), "{\"tiers\": {\"TIER_1\": {\"model_id\": \"\"}}}");
        Path notJson = Files.writeString(dir.resolve("broken.json"), "{");

        assertThatThrownBy(() -> loader.load(dir, false, Optional.of(unknownKey)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("typo.json");
        assertThatThrownBy(() -> loader.load(dir, false, Optional.of(missingTierModel)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("modelId");
        assertThatThrownBy(() -> loader.load(dir, false, Optional.of(notJson)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not valid JSON");
    }
}
