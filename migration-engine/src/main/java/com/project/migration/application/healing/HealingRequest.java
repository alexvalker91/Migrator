package com.project.migration.application.healing;

import com.project.migration.domain.model.Checks;
import com.project.migration.domain.model.code.CodeChange;
import com.project.migration.domain.model.code.TestMethodRef;
import com.project.migration.domain.model.report.HealingAttempt;
import com.project.migration.domain.model.run.TestRunResult;
import com.project.migration.domain.model.vcs.CommitId;

import java.util.List;

/**
 * One healing attempt for a failing test.
 *
 * @param failedRun        the failing run to diagnose
 * @param attemptNumber    1-based
 * @param passesBeforeFailure runs of this test that passed since the last change of the code, before the failure
 * @param baseline         last commit; a reverted attempt returns the files to it
 * @param appliedBatches   change batches of the earlier attempts of this test, in order; they are re-applied
 *                         after a revert so earlier progress is not lost
 * @param previousAttempts what the earlier attempts diagnosed and did
 */
public record HealingRequest(
        TestMethodRef test,
        TestRunResult failedRun,
        int attemptNumber,
        int passesBeforeFailure,
        CommitId baseline,
        List<List<CodeChange>> appliedBatches,
        List<HealingAttempt> previousAttempts) {

    public HealingRequest {
        Checks.notNull(test, "test");
        Checks.notNull(failedRun, "failedRun");
        Checks.positive(attemptNumber, "attemptNumber");
        Checks.nonNegative(passesBeforeFailure, "passesBeforeFailure");
        Checks.notNull(baseline, "baseline");
        appliedBatches = List.copyOf(Checks.notNull(appliedBatches, "appliedBatches"));
        previousAttempts = List.copyOf(Checks.notNull(previousAttempts, "previousAttempts"));
    }
}
