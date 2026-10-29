package com.project.migration.application.report;

import com.project.migration.domain.model.history.ClassRunSummary;
import com.project.migration.domain.model.history.ScalingProjection;
import com.project.migration.domain.model.history.ScalingProjection.DeviceEstimate;
import com.project.migration.domain.model.history.ScalingProjection.LearningPoint;
import com.project.migration.domain.model.history.ScalingProjection.Scenario;
import com.project.migration.domain.model.history.ScalingProjection.ScenarioKind;
import com.project.migration.domain.model.report.ProjectProgress;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * Scaling projection from recorded runs, with no invented coefficients:
 * <ul>
 * <li><b>expected</b>: cost, tokens and time per test over the last {@value #WINDOW} runs, weighted by the tests of each run;</li>
 * <li><b>pessimistic</b>: the worst run of that window, per figure;</li>
 * <li><b>optimistic</b>: the best run of that window, without the very first run (its cache was empty).</li>
 * </ul>
 * Each unit figure is multiplied by the tests still to migrate; the duration is also spread over 1, 2 and 4 devices
 * (classes can run on separate devices). Fewer than {@value #CONFIDENT_RUNS} runs are flagged as low confidence.
 */
public final class ProjectionCalculator {

    static final int WINDOW = 5;
    static final int CONFIDENT_RUNS = 3;
    private static final int[] DEVICES = {1, 2, 4};
    private static final int CURVE_LIMIT = 60;

    private ProjectionCalculator() {
    }

    /** @param history every recorded run, oldest first, including the one being reported */
    public static ScalingProjection project(List<ClassRunSummary> history, ProjectProgress progress) {
        List<ClassRunSummary> runs = history.stream().filter(run -> run.processedTests() > 0).toList();
        int remaining = Math.max(0, progress.totalTests() - progress.migratedTests());
        List<LearningPoint> curve = learningCurve(runs);
        if (runs.isEmpty()) {
            return new ScalingProjection(remaining, 0, true, List.of(), curve);
        }
        List<ClassRunSummary> window = runs.subList(Math.max(0, runs.size() - WINDOW), runs.size());
        List<ClassRunSummary> withoutFirst = window.stream().filter(run -> run != runs.getFirst()).toList();
        List<ClassRunSummary> best = withoutFirst.isEmpty() ? window : withoutFirst;

        List<Scenario> scenarios = List.of(
                scenario(ScenarioKind.PESSIMISTIC, remaining, worst(window)),
                scenario(ScenarioKind.EXPECTED, remaining, weighted(window)),
                scenario(ScenarioKind.OPTIMISTIC, remaining, bestOf(best)));
        return new ScalingProjection(remaining, runs.size(), runs.size() < CONFIDENT_RUNS, scenarios, curve);
    }

    private static Scenario scenario(ScenarioKind kind, int remaining, Unit unit) {
        BigDecimal totalCost = unit.cost().multiply(BigDecimal.valueOf(remaining)).setScale(2, RoundingMode.HALF_UP);
        Duration total = unit.duration().multipliedBy(remaining);
        List<DeviceEstimate> wallClock = new ArrayList<>();
        for (int devices : DEVICES) {
            wallClock.add(new DeviceEstimate(devices, ceilDivide(total, devices)));
        }
        return new Scenario(kind, unit.cost().setScale(4, RoundingMode.HALF_UP), unit.tokens(), unit.duration(), totalCost,
                Math.round(unit.tokens() * remaining), total, wallClock);
    }

    private static Unit weighted(List<ClassRunSummary> runs) {
        long tests = runs.stream().mapToLong(ClassRunSummary::processedTests).sum();
        BigDecimal cost = runs.stream().map(ClassRunSummary::cost).reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(tests), 6, RoundingMode.HALF_UP);
        double tokens = (double) runs.stream().mapToLong(ClassRunSummary::totalTokens).sum() / tests;
        Duration duration = runs.stream().map(ClassRunSummary::duration).reduce(Duration.ZERO, Duration::plus)
                .dividedBy(tests);
        return new Unit(cost, tokens, duration);
    }

    private static Unit worst(List<ClassRunSummary> runs) {
        return new Unit(extreme(runs, ClassRunSummary::costPerTest, true), extreme(runs, ClassRunSummary::tokensPerTest, true),
                extreme(runs, ClassRunSummary::durationPerTest, true));
    }

    private static Unit bestOf(List<ClassRunSummary> runs) {
        return new Unit(extreme(runs, ClassRunSummary::costPerTest, false), extreme(runs, ClassRunSummary::tokensPerTest, false),
                extreme(runs, ClassRunSummary::durationPerTest, false));
    }

    private static <T extends Comparable<T>> T extreme(List<ClassRunSummary> runs, Function<ClassRunSummary, T> figure, boolean max) {
        Comparator<T> order = Comparator.naturalOrder();
        return runs.stream().map(figure).max(max ? order : order.reversed()).orElseThrow();
    }

    private static List<LearningPoint> learningCurve(List<ClassRunSummary> runs) {
        int from = Math.max(0, runs.size() - CURVE_LIMIT);
        List<LearningPoint> curve = new ArrayList<>();
        for (int i = from; i < runs.size(); i++) {
            ClassRunSummary run = runs.get(i);
            curve.add(new LearningPoint(i + 1, run.testClassFqcn(), run.costPerTest(), run.tokensPerTest(),
                    run.durationPerTest(), run.cacheHitRate()));
        }
        return curve;
    }

    private static Duration ceilDivide(Duration total, int devices) {
        long millis = total.toMillis();
        return Duration.ofMillis((millis + devices - 1) / devices);
    }

    private record Unit(BigDecimal cost, double tokens, Duration duration) {
    }
}
