package com.project.migration.domain.model.report;

import com.project.migration.domain.model.Checks;
import com.project.migration.domain.model.code.TestMethodRef;
import com.project.migration.domain.model.orchestration.MigrationExitStatus;
import com.project.migration.domain.model.MigrationStage;
import com.project.migration.domain.model.run.TestClassRunResult;
import com.project.migration.domain.model.telemetry.TelemetrySnapshot;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Everything the report generator needs for {@code dashboard.html} and {@code report.md} of one class.
 *
 * @param exitStatus         how the migration ended
 * @param androidRegressions methods that failed on Android in the guard run and again when run alone (the
 *                           {@code androidRegressionRun} may also hold flakes that passed on the second try)
 */
public record ClassMigrationReport(
        String testClassFqcn,
        String branch,
        Instant startedAt,
        Instant finishedAt,
        List<TestMethodReport> tests,
        Map<MigrationStage, Duration> stageTimings,
        TelemetrySnapshot telemetry,
        Optional<TestClassRunResult> iosClassRun,
        Optional<TestClassRunResult> androidRegressionRun,
        ProjectProgress progress,
        MigrationExitStatus exitStatus,
        List<TestMethodRef> androidRegressions) {

    public ClassMigrationReport {
        Checks.notBlank(testClassFqcn, "testClassFqcn");
        Checks.notBlank(branch, "branch");
        Checks.notNull(startedAt, "startedAt");
        Checks.notNull(finishedAt, "finishedAt");
        if (finishedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("finishedAt must not be before startedAt");
        }
        tests = List.copyOf(Checks.notNull(tests, "tests"));
        stageTimings = Map.copyOf(Checks.notNull(stageTimings, "stageTimings"));
        Checks.notNull(telemetry, "telemetry");
        iosClassRun = iosClassRun == null ? Optional.empty() : iosClassRun;
        androidRegressionRun = androidRegressionRun == null ? Optional.empty() : androidRegressionRun;
        Checks.notNull(progress, "progress");
        Checks.notNull(exitStatus, "exitStatus");
        androidRegressions = List.copyOf(Checks.notNull(androidRegressions, "androidRegressions"));
    }

    public Duration totalDuration() {
        return Duration.between(startedAt, finishedAt);
    }

    public Duration averageTestDuration() {
        return tests.isEmpty() ? Duration.ZERO : totalDuration().dividedBy(tests.size());
    }

    public long count(TestMigrationStatus status) {
        return tests.stream().filter(t -> t.status() == status).count();
    }
}
