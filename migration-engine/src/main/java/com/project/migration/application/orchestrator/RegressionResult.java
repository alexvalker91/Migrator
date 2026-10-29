package com.project.migration.application.orchestrator;

import com.project.migration.domain.model.Checks;
import com.project.migration.domain.model.code.TestMethodRef;
import com.project.migration.domain.model.run.TestClassRunResult;

import java.util.List;

/**
 * Result of the Android regression guard.
 *
 * @param run         the first class run on Android, as it happened
 * @param regressions methods that failed in the class run and again when run alone
 */
public record RegressionResult(TestClassRunResult run, List<TestMethodRef> regressions) {

    public RegressionResult {
        Checks.notNull(run, "run");
        regressions = List.copyOf(Checks.notNull(regressions, "regressions"));
    }

    public boolean passed() {
        return regressions.isEmpty();
    }
}
