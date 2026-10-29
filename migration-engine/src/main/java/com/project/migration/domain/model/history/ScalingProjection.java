package com.project.migration.domain.model.history;

import com.project.migration.domain.model.Checks;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

/**
 * What migrating the rest of the project is expected to cost, extrapolated from the classes migrated so far.
 *
 * @param remainingTests   tests still to migrate
 * @param classesInHistory finished class runs the figures are based on
 * @param lowConfidence    too few runs to trust the figures (they are estimates, not forecasts)
 * @param scenarios        pessimistic, expected and optimistic; empty without history
 * @param learningCurve    cost per test of every recorded run in order, to show the cache paying off
 */
public record ScalingProjection(
        int remainingTests,
        int classesInHistory,
        boolean lowConfidence,
        List<Scenario> scenarios,
        List<LearningPoint> learningCurve) {

    public enum ScenarioKind {
        PESSIMISTIC,
        EXPECTED,
        OPTIMISTIC
    }

    /**
     * @param costPerTest/tokensPerTest/durationPerTest unit figures of the scenario
     * @param totalCost/totalTokens/totalDuration       unit figures times the remaining tests
     * @param wallClock                                 total duration spread over 1, 2 and 4 devices
     */
    public record Scenario(
            ScenarioKind kind,
            BigDecimal costPerTest,
            double tokensPerTest,
            Duration durationPerTest,
            BigDecimal totalCost,
            long totalTokens,
            Duration totalDuration,
            List<DeviceEstimate> wallClock) {

        public Scenario {
            Checks.notNull(kind, "kind");
            Checks.notNull(costPerTest, "costPerTest");
            Checks.notNull(durationPerTest, "durationPerTest");
            Checks.notNull(totalCost, "totalCost");
            Checks.notNull(totalDuration, "totalDuration");
            wallClock = List.copyOf(Checks.notNull(wallClock, "wallClock"));
        }
    }

    public record DeviceEstimate(int devices, Duration wallClock) {
        public DeviceEstimate {
            Checks.positive(devices, "devices");
            Checks.notNull(wallClock, "wallClock");
        }
    }

    /** @param index 1-based position of the run in the history */
    public record LearningPoint(int index, String testClassFqcn, BigDecimal costPerTest, double tokensPerTest,
                                Duration durationPerTest, double cacheHitRate) {
        public LearningPoint {
            Checks.positive(index, "index");
            Checks.notBlank(testClassFqcn, "testClassFqcn");
            Checks.notNull(costPerTest, "costPerTest");
            Checks.notNull(durationPerTest, "durationPerTest");
        }
    }

    public ScalingProjection {
        Checks.nonNegative(remainingTests, "remainingTests");
        Checks.nonNegative(classesInHistory, "classesInHistory");
        scenarios = List.copyOf(Checks.notNull(scenarios, "scenarios"));
        learningCurve = List.copyOf(Checks.notNull(learningCurve, "learningCurve"));
    }

    public boolean hasScenarios() {
        return !scenarios.isEmpty();
    }

    public Scenario scenario(ScenarioKind kind) {
        return scenarios.stream().filter(s -> s.kind() == kind).findFirst().orElseThrow();
    }
}
