package com.project.migration.application.report;

import com.project.migration.domain.model.history.ClassRunSummary;
import com.project.migration.domain.model.history.ScalingProjection;
import com.project.migration.domain.model.history.ScalingProjection.ScenarioKind;
import com.project.migration.domain.model.orchestration.MigrationExitStatus;
import com.project.migration.domain.model.report.ProjectProgress;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectionCalculatorTest {

    static final ProjectProgress PROGRESS = new ProjectProgress(1200, 200, 50, 10);

    @Test
    void withoutHistoryThereAreNoScenariosAndTheFlagIsRaised() {
        ScalingProjection projection = ProjectionCalculator.project(List.of(), PROGRESS);

        assertThat(projection.hasScenarios()).isFalse();
        assertThat(projection.lowConfidence()).isTrue();
        assertThat(projection.classesInHistory()).isZero();
        assertThat(projection.remainingTests()).isEqualTo(1000);
        assertThat(projection.learningCurve()).isEmpty();
    }

    @Test
    void expectedScenarioIsTheTestWeightedAverageOfTheLastFiveRuns() {
        List<ClassRunSummary> history = List.of(run("A", 10, "10.00", 100_000, 60), run("B", 20, "10.00", 100_000, 60),
                run("C", 10, "5.00", 50_000, 30));

        ScalingProjection.Scenario expected = ProjectionCalculator.project(history, PROGRESS).scenario(ScenarioKind.EXPECTED);

        // cost 25.00 over 40 tests, 250k tokens over 40 tests, 150 min over 40 tests
        assertThat(expected.costPerTest()).isEqualByComparingTo("0.6250");
        assertThat(expected.tokensPerTest()).isEqualTo(6_250.0);
        assertThat(expected.durationPerTest()).isEqualTo(Duration.ofSeconds(225));
        assertThat(expected.totalCost()).isEqualByComparingTo("625.00");
        assertThat(expected.totalTokens()).isEqualTo(6_250_000);
        assertThat(expected.totalDuration()).isEqualTo(Duration.ofSeconds(225_000));
    }

    @Test
    void pessimisticTakesTheWorstRunAndOptimisticTheBestWithoutTheFirstRun() {
        List<ClassRunSummary> history = List.of(
                run("First", 10, "1.00", 10_000, 6),        // 0.10 per test: cheapest, but its cache was empty... still the first run
                run("Second", 10, "20.00", 200_000, 120),   // 2.00 per test
                run("Third", 10, "4.00", 40_000, 24),       // 0.40 per test
                run("Fourth", 10, "3.00", 30_000, 18));     // 0.30 per test

        ScalingProjection projection = ProjectionCalculator.project(history, PROGRESS);

        assertThat(projection.scenario(ScenarioKind.PESSIMISTIC).costPerTest()).isEqualByComparingTo("2.0000");
        assertThat(projection.scenario(ScenarioKind.PESSIMISTIC).totalCost()).isEqualByComparingTo("2000.00");
        assertThat(projection.scenario(ScenarioKind.OPTIMISTIC).costPerTest()).isEqualByComparingTo("0.3000");
        assertThat(projection.scenario(ScenarioKind.OPTIMISTIC).tokensPerTest()).isEqualTo(3_000.0);
        assertThat(projection.scenario(ScenarioKind.OPTIMISTIC).durationPerTest()).isEqualTo(Duration.ofSeconds(108));
    }

    @Test
    void aSingleRunIsUsedForEveryScenarioAndIsFlaggedAsLowConfidence() {
        ScalingProjection projection = ProjectionCalculator.project(List.of(run("A", 10, "5.00", 50_000, 30)), PROGRESS);

        assertThat(projection.lowConfidence()).isTrue();
        assertThat(projection.scenarios()).extracting(s -> s.costPerTest().toPlainString()).containsOnly("0.5000");
    }

    @Test
    void threeRunsAreEnoughToDropTheLowConfidenceFlag() {
        List<ClassRunSummary> three = List.of(run("A", 10, "5.00", 1, 1), run("B", 10, "5.00", 1, 1), run("C", 10, "5.00", 1, 1));

        assertThat(ProjectionCalculator.project(three, PROGRESS).lowConfidence()).isFalse();
        assertThat(ProjectionCalculator.project(three.subList(0, 2), PROGRESS).lowConfidence()).isTrue();
    }

    @Test
    void onlyTheLastFiveRunsCountForTheScenariosButTheCurveShowsAll() {
        List<ClassRunSummary> history = new ArrayList<>();
        history.add(run("Old", 10, "1000.00", 1, 600));
        for (int i = 0; i < 5; i++) {
            history.add(run("R" + i, 10, "10.00", 100_000, 60));
        }

        ScalingProjection projection = ProjectionCalculator.project(history, PROGRESS);

        assertThat(projection.scenario(ScenarioKind.PESSIMISTIC).costPerTest()).isEqualByComparingTo("1.0000");
        assertThat(projection.learningCurve()).hasSize(6);
        assertThat(projection.learningCurve().getFirst().costPerTest()).isEqualByComparingTo("100.0");
        assertThat(projection.learningCurve().getLast().index()).isEqualTo(6);
    }

    @Test
    void wallClockIsSpreadOverDevicesAndRoundedUp() {
        ScalingProjection.Scenario scenario = ProjectionCalculator.project(List.of(run("A", 10, "5.00", 50_000, 30)),
                new ProjectProgress(103, 100, 5, 1)).scenario(ScenarioKind.EXPECTED);

        // 3 remaining tests * 180 s = 540 s
        assertThat(scenario.totalDuration()).isEqualTo(Duration.ofSeconds(540));
        assertThat(scenario.wallClock()).extracting(e -> e.devices() + ":" + e.wallClock().toSeconds())
                .containsExactly("1:540", "2:270", "4:135");
    }

    @Test
    void nothingRemainingMeansZeroTotalsAndRunsWithoutProcessedTestsAreIgnored() {
        ScalingProjection done = ProjectionCalculator.project(List.of(run("A", 10, "5.00", 50_000, 30), run("Empty", 0, "0", 0, 0)),
                new ProjectProgress(10, 10, 1, 1));

        assertThat(done.remainingTests()).isZero();
        assertThat(done.classesInHistory()).isEqualTo(1);
        assertThat(done.scenario(ScenarioKind.EXPECTED).totalCost()).isEqualByComparingTo("0");
        assertThat(done.scenario(ScenarioKind.EXPECTED).totalDuration()).isEqualTo(Duration.ZERO);
    }

    static ClassRunSummary run(String name, int processed, String cost, long tokens, int minutes) {
        return new ClassRunSummary("com.bank." + name, Instant.parse("2026-10-30T10:00:00Z"), MigrationExitStatus.SUCCESS,
                processed, processed, 0, 0, 0, tokens, new BigDecimal(cost), 0, BigDecimal.ZERO, 0.5, Duration.ofMinutes(minutes),
                Duration.ofMinutes(minutes), Duration.ZERO);
    }
}
