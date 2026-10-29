package com.project.migration.application.stability;

import com.project.migration.domain.model.Checks;
import com.project.migration.domain.model.report.FailureEvidence;
import com.project.migration.domain.model.report.HealingAttempt;
import com.project.migration.domain.model.report.TestMigrationStatus;
import com.project.migration.domain.model.vcs.CommitId;

import java.util.List;
import java.util.Optional;

/**
 * Result of the gate for one test.
 *
 * @param head the commit after the gate (the checkpoint on success, the isolation commit or the baseline on failure)
 */
public record GateOutcome(
        TestMigrationStatus status,
        GateFailureKind failureKind,
        int passStreak,
        int totalRuns,
        List<HealingAttempt> healingAttempts,
        Optional<FailureEvidence> evidence,
        CommitId head) {

    public GateOutcome {
        Checks.notNull(status, "status");
        Checks.notNull(failureKind, "failureKind");
        Checks.nonNegative(passStreak, "passStreak");
        Checks.nonNegative(totalRuns, "totalRuns");
        healingAttempts = List.copyOf(Checks.notNull(healingAttempts, "healingAttempts"));
        evidence = evidence == null ? Optional.empty() : evidence;
        Checks.notNull(head, "head");
    }

    public boolean passed() {
        return failureKind == GateFailureKind.NONE;
    }
}
