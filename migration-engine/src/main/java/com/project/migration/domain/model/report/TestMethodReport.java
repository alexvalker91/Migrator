package com.project.migration.domain.model.report;

import com.project.migration.domain.model.Checks;
import com.project.migration.domain.model.MigrationStage;
import com.project.migration.domain.model.code.TestMethodRef;
import com.project.migration.domain.model.telemetry.LlmCallRecord;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Per-test section of the class report.
 *
 * @param passStreak consecutive passes at the end of the stability gate
 * @param totalRuns  all iOS runs of this test, including failed ones
 */
public record TestMethodReport(
        TestMethodRef test,
        TestMigrationStatus status,
        int passStreak,
        int totalRuns,
        List<HealingAttempt> healingAttempts,
        Optional<FailureEvidence> failureEvidence,
        List<LlmCallRecord> llmCalls,
        Map<MigrationStage, Duration> stageTimings) {

    public TestMethodReport {
        Checks.notNull(test, "test");
        Checks.notNull(status, "status");
        Checks.nonNegative(passStreak, "passStreak");
        Checks.nonNegative(totalRuns, "totalRuns");
        if (passStreak > totalRuns) {
            throw new IllegalArgumentException("passStreak must not exceed totalRuns");
        }
        healingAttempts = List.copyOf(Checks.notNull(healingAttempts, "healingAttempts"));
        failureEvidence = failureEvidence == null ? Optional.empty() : failureEvidence;
        llmCalls = List.copyOf(Checks.notNull(llmCalls, "llmCalls"));
        stageTimings = Map.copyOf(Checks.notNull(stageTimings, "stageTimings"));

        switch (status) {
            case STABLE_PASS -> require(healingAttempts.isEmpty(), "STABLE_PASS must not have healing attempts");
            case HEALED_AND_PASSED -> require(!healingAttempts.isEmpty(), "HEALED_AND_PASSED needs healing attempts");
            case NEEDS_MANUAL_FIX -> require(failureEvidence.isPresent(), "NEEDS_MANUAL_FIX needs failure evidence");
            default -> {
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    public Duration totalDuration() {
        return stageTimings.values().stream().reduce(Duration.ZERO, Duration::plus);
    }
}
