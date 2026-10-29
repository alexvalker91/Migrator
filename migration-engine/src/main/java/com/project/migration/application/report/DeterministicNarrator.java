package com.project.migration.application.report;

import com.project.migration.domain.model.report.ClassMigrationReport;
import com.project.migration.domain.model.report.TestMethodReport;
import com.project.migration.domain.model.report.TestMigrationStatus;
import com.project.migration.domain.model.run.TestClassRunResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Prose assembled from the data alone: always available, used when the model is not.
 */
public final class DeterministicNarrator implements ReportNarrator {

    private static final int VERDICT_LIMIT = 200;

    @Override
    public ReportNarrative narrate(ClassMigrationReport report) {
        List<String> summary = new ArrayList<>();
        long processed = report.count(TestMigrationStatus.STABLE_PASS) + report.count(TestMigrationStatus.HEALED_AND_PASSED)
                + report.count(TestMigrationStatus.NEEDS_MANUAL_FIX);
        summary.add(String.format(Locale.ROOT, "%d of %d tests were migrated now: %d stable, %d after self-healing, %d need a manual fix.",
                report.count(TestMigrationStatus.STABLE_PASS) + report.count(TestMigrationStatus.HEALED_AND_PASSED), processed,
                report.count(TestMigrationStatus.STABLE_PASS), report.count(TestMigrationStatus.HEALED_AND_PASSED),
                report.count(TestMigrationStatus.NEEDS_MANUAL_FIX)));
        summary.add("Final iOS run of the class: " + runText(report.iosClassRun().orElse(null)) + ". Android regression guard: "
                + (report.androidRegressionRun().isEmpty() ? "not run"
                : report.androidRegressions().isEmpty() ? "no regressions" : report.androidRegressions().size() + " regression(s)") + ".");
        summary.add(String.format(Locale.ROOT, "LLM cost $%s for %d tokens; %d tokens saved by XML cleaning and the cache, cache hit rate %.0f%%.",
                report.telemetry().totalCost().setScale(2, java.math.RoundingMode.HALF_UP), report.telemetry().totalUsage().total(),
                report.telemetry().totalSavedTokens(), report.telemetry().cacheStatistics().hitRate() * 100));
        Map<String, String> nextSteps = new LinkedHashMap<>();
        for (TestMethodReport test : report.tests()) {
            if (test.status() == TestMigrationStatus.NEEDS_MANUAL_FIX) {
                String verdict = test.failureEvidence().map(e -> e.verdict()).orElse("no verdict recorded");
                nextSteps.put(test.test().methodName(), "Open the failure evidence and fix it by hand: "
                        + (verdict.length() <= VERDICT_LIMIT ? verdict : verdict.substring(0, VERDICT_LIMIT) + "..."));
            }
        }
        return new ReportNarrative(summary, nextSteps, false);
    }

    private static String runText(TestClassRunResult run) {
        return run == null ? "not run" : run.allPassed() ? "green" : "red";
    }
}
