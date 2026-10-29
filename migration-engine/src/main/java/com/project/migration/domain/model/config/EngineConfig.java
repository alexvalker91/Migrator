package com.project.migration.domain.model.config;

import com.project.migration.domain.model.Checks;
import com.project.migration.domain.model.code.FrameworkContract;
import com.project.migration.domain.model.llm.LlmTier;
import com.project.migration.domain.model.llm.TierModelConfig;
import com.project.migration.domain.model.telemetry.ModelPricing;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Engine settings. Loaded by infrastructure; secrets ({@code DIAL_API_KEY}, {@code MOBITRU_AUTH_TOKEN}) are
 * never part of it and are read from the environment by the adapters that need them.
 *
 * @param projectRoot  root of the target test project ({@code --project-root})
 * @param migrationDir local knowledge base ({@code .migration/})
 * @param reportsDir   per-class reports ({@code migration_reports/})
 * @param sourceRoots  Java source roots of the target project; empty = every {@code src/main/java} and
 *                     {@code src/test/java} found under {@code projectRoot}
 * @param framework    base classes and helper contract of the target project
 */
public record EngineConfig(
        Path projectRoot,
        Path migrationDir,
        Path reportsDir,
        boolean showReasoning,
        int stabilityRuns,
        int maxHealingAttempts,
        List<TierModelConfig> tierModels,
        List<ModelPricing> pricing,
        List<Path> sourceRoots,
        FrameworkContract framework) {

    public static final int DEFAULT_STABILITY_RUNS = 3;
    public static final int DEFAULT_MAX_HEALING_ATTEMPTS = 3;

    public EngineConfig {
        Checks.notNull(projectRoot, "projectRoot");
        Checks.notNull(migrationDir, "migrationDir");
        Checks.notNull(reportsDir, "reportsDir");
        Checks.positive(stabilityRuns, "stabilityRuns");
        Checks.nonNegative(maxHealingAttempts, "maxHealingAttempts");
        tierModels = List.copyOf(Checks.notNull(tierModels, "tierModels"));
        Set<LlmTier> configured = EnumSet.noneOf(LlmTier.class);
        for (TierModelConfig model : tierModels) {
            if (!configured.add(model.tier())) {
                throw new IllegalArgumentException("tier configured twice: " + model.tier());
            }
        }
        Set<LlmTier> missing = EnumSet.allOf(LlmTier.class);
        missing.removeAll(configured);
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("every tier needs a model, missing: " + missing);
        }
        if (tierModels.stream().noneMatch(m -> m.tier() == LlmTier.TIER_1 && m.supportsVision())) {
            throw new IllegalArgumentException("TIER_1 model must support vision (self-healing uses screenshots)");
        }
        pricing = List.copyOf(Checks.notNull(pricing, "pricing"));
        if (pricing.stream().map(ModelPricing::modelId).distinct().count() != pricing.size()) {
            throw new IllegalArgumentException("duplicate pricing for a model");
        }
        sourceRoots = List.copyOf(Checks.notNull(sourceRoots, "sourceRoots"));
        Checks.notNull(framework, "framework");
    }

    /**
     * Standard layout: {@code <projectRoot>/.migration} and {@code <projectRoot>/migration_reports}, 3/3 gate,
     * 3 heals, auto-discovered source roots, {@link FrameworkContract#DEFAULT}.
     */
    public static EngineConfig withDefaults(
            Path projectRoot, boolean showReasoning, List<TierModelConfig> tierModels, List<ModelPricing> pricing) {
        return new EngineConfig(projectRoot, projectRoot.resolve(".migration"), projectRoot.resolve("migration_reports"),
                showReasoning, DEFAULT_STABILITY_RUNS, DEFAULT_MAX_HEALING_ATTEMPTS, tierModels, pricing,
                List.of(), FrameworkContract.DEFAULT);
    }

    public EngineConfig withSourceRoots(List<Path> roots) {
        return new EngineConfig(projectRoot, migrationDir, reportsDir, showReasoning, stabilityRuns,
                maxHealingAttempts, tierModels, pricing, roots, framework);
    }

    public EngineConfig withFramework(FrameworkContract contract) {
        return new EngineConfig(projectRoot, migrationDir, reportsDir, showReasoning, stabilityRuns,
                maxHealingAttempts, tierModels, pricing, sourceRoots, contract);
    }

    public TierModelConfig modelFor(LlmTier tier) {
        return tierModels.stream().filter(m -> m.tier() == tier).findFirst().orElseThrow();
    }

    public Optional<ModelPricing> pricingFor(String modelId) {
        return pricing.stream().filter(p -> p.modelId().equals(modelId)).findFirst();
    }

    /** {@code migration_reports/<TestClass>/} */
    public Path reportDirFor(String testClassSimpleName) {
        return reportsDir.resolve(Checks.notBlank(testClassSimpleName, "testClassSimpleName"));
    }
}
