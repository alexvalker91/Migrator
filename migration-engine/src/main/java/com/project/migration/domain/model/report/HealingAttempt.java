package com.project.migration.domain.model.report;

import com.project.migration.domain.model.Checks;

import java.util.Optional;

/**
 * One self-healing attempt for a failing test.
 */
public record HealingAttempt(
        int attemptNumber,
        String diagnosis,
        Optional<String> reasoning,
        String changeSummary,
        HealingOutcome outcome) {

    public HealingAttempt {
        Checks.positive(attemptNumber, "attemptNumber");
        Checks.notBlank(diagnosis, "diagnosis");
        reasoning = reasoning == null ? Optional.empty() : reasoning;
        Checks.notBlank(changeSummary, "changeSummary");
        Checks.notNull(outcome, "outcome");
    }
}
