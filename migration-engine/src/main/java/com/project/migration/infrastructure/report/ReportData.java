package com.project.migration.infrastructure.report;

import com.fasterxml.jackson.core.JacksonException;
import com.project.migration.domain.model.MigrationStage;
import com.project.migration.domain.model.history.ScalingProjection;
import com.project.migration.domain.model.llm.LlmTier;
import com.project.migration.domain.model.report.ClassMigrationReport;
import com.project.migration.domain.model.report.TestMigrationStatus;
import com.project.migration.domain.model.telemetry.SavingsSource;
import com.project.migration.domain.model.telemetry.TelemetrySnapshot;
import com.project.migration.domain.model.telemetry.TokenUsage;
import com.project.migration.infrastructure.json.JsonMappers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The numbers behind the charts, as one JSON object embedded in the dashboard.
 */
final class ReportData {

    /** Stage groups of the time chart, in the wording of the specification. */
    static Map<String, Duration> stageGroups(Map<MigrationStage, Duration> timings) {
        Map<String, Duration> groups = new LinkedHashMap<>();
        groups.put("Analysis", timings.getOrDefault(MigrationStage.STAGE_1_DELTA_ANALYSIS, Duration.ZERO));
        groups.put("MCP snapshots", timings.getOrDefault(MigrationStage.STAGE_2_SNAPSHOTS, Duration.ZERO)
                .plus(timings.getOrDefault(MigrationStage.STAGE_0_GLOBALS, Duration.ZERO)));
        groups.put("Code generation", timings.getOrDefault(MigrationStage.STAGE_3_CODEGEN, Duration.ZERO));
        groups.put("3x runs", timings.getOrDefault(MigrationStage.STAGE_4_STABILIZATION, Duration.ZERO));
        groups.put("Self-healing", timings.getOrDefault(MigrationStage.STAGE_4_SELF_HEALING, Duration.ZERO));
        groups.put("Final checks", timings.getOrDefault(MigrationStage.STAGE_5_FINALIZE, Duration.ZERO));
        return groups;
    }

    static String json(ClassMigrationReport report, ScalingProjection projection) {
        Map<String, Object> data = new LinkedHashMap<>();
        TelemetrySnapshot telemetry = report.telemetry();
        Map<LlmTier, TokenUsage> usage = new EnumMap<>(LlmTier.class);
        usage.putAll(telemetry.usageByTier());
        List<String> tiers = new ArrayList<>();
        List<Long> input = new ArrayList<>();
        List<Long> output = new ArrayList<>();
        List<Long> reasoning = new ArrayList<>();
        List<Double> cost = new ArrayList<>();
        for (LlmTier tier : LlmTier.values()) {
            TokenUsage u = usage.getOrDefault(tier, TokenUsage.ZERO);
            tiers.add(tier.name().replace("TIER_", "Tier "));
            input.add(u.input() - u.cachedInput());
            output.add(u.output());
            reasoning.add(u.reasoning());
            cost.add(telemetry.costByTier().getOrDefault(tier, java.math.BigDecimal.ZERO).doubleValue());
        }
        data.put("tokens", Map.of("labels", tiers, "input", input, "output", output, "reasoning", reasoning));
        data.put("cost", Map.of("labels", tiers, "values", cost));

        Map<String, Duration> groups = stageGroups(report.stageTimings());
        data.put("stages", Map.of("labels", List.copyOf(groups.keySet()),
                "values", groups.values().stream().map(Duration::toSeconds).toList()));

        Map<String, Long> statuses = new LinkedHashMap<>();
        for (TestMigrationStatus status : TestMigrationStatus.values()) {
            long count = report.count(status);
            if (count > 0) {
                statuses.put(status.name(), count);
            }
        }
        data.put("statuses", Map.of("labels", List.copyOf(statuses.keySet()), "values", List.copyOf(statuses.values())));

        List<String> sources = new ArrayList<>();
        List<Long> saved = new ArrayList<>();
        for (SavingsSource source : SavingsSource.values()) {
            sources.add(source.name());
            saved.add(telemetry.savedTokens(source));
        }
        data.put("savings", Map.of("labels", sources, "values", saved));

        data.put("learning", learning(projection));
        data.put("projection", projection(projection));
        try {
            return HtmlEscaper.jsonInScript(JsonMappers.llm().writeValueAsString(data));
        } catch (JacksonException e) {
            throw new IllegalStateException("Cannot serialize the chart data", e);
        }
    }

    private static Map<String, Object> learning(ScalingProjection projection) {
        List<String> labels = new ArrayList<>();
        List<Double> cost = new ArrayList<>();
        List<Double> minutes = new ArrayList<>();
        List<Double> hitRate = new ArrayList<>();
        projection.learningCurve().forEach(point -> {
            labels.add("#" + point.index() + " " + point.testClassFqcn().substring(point.testClassFqcn().lastIndexOf('.') + 1));
            cost.add(point.costPerTest().doubleValue());
            minutes.add(point.durationPerTest().toSeconds() / 60.0);
            hitRate.add(point.cacheHitRate() * 100);
        });
        return Map.of("labels", labels, "cost", cost, "minutes", minutes, "hitRate", hitRate);
    }

    private static Map<String, Object> projection(ScalingProjection projection) {
        List<String> labels = new ArrayList<>();
        List<Double> cost = new ArrayList<>();
        Map<Integer, List<Double>> hours = new LinkedHashMap<>();
        for (ScalingProjection.Scenario scenario : projection.scenarios()) {
            labels.add(scenario.kind().name().charAt(0) + scenario.kind().name().substring(1).toLowerCase());
            cost.add(scenario.totalCost().doubleValue());
            scenario.wallClock().forEach(e -> hours.computeIfAbsent(e.devices(), d -> new ArrayList<>())
                    .add(Math.round(ReportFormat.hours(e.wallClock()) * 10) / 10.0));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("labels", labels);
        out.put("cost", cost);
        List<Map<String, Object>> devices = new ArrayList<>();
        hours.forEach((count, values) -> devices.add(Map.of("devices", count, "hours", values)));
        out.put("devices", devices);
        return out;
    }

    private ReportData() {
    }
}
