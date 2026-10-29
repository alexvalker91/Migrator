package com.project.migration.application.report;

import com.project.migration.domain.model.MigrationStage;
import com.project.migration.domain.model.history.ClassRunSummary;
import com.project.migration.domain.model.orchestration.MigrationExitStatus;
import com.project.migration.domain.model.report.ClassMigrationReport;
import com.project.migration.domain.model.report.TestMethodReport;
import com.project.migration.domain.model.report.TestMigrationStatus;
import com.project.migration.domain.model.telemetry.TelemetrySnapshot;

/**
 * Condenses a finished class report into the line that goes to the run history.
 */
public final class RunSummaryFactory {

    private RunSummaryFactory() {
    }

    public static ClassRunSummary of(ClassMigrationReport report, MigrationExitStatus status) {
        TelemetrySnapshot telemetry = report.telemetry();
        int stable = (int) report.count(TestMigrationStatus.STABLE_PASS);
        int healed = (int) report.count(TestMigrationStatus.HEALED_AND_PASSED);
        int manual = (int) report.count(TestMigrationStatus.NEEDS_MANUAL_FIX);
        int attempts = report.tests().stream().mapToInt(TestMethodReport::healingAttemptCount).sum();
        return new ClassRunSummary(report.testClassFqcn(), report.finishedAt(), status, stable + healed + manual, stable,
                healed, manual, attempts, telemetry.totalUsage().total(), telemetry.totalCost(), telemetry.totalSavedTokens(),
                telemetry.estimatedSavedCost(), telemetry.cacheStatistics().hitRate(), report.totalDuration(),
                telemetry.stageDuration(MigrationStage.STAGE_4_STABILIZATION),
                telemetry.stageDuration(MigrationStage.STAGE_4_SELF_HEALING));
    }
}
