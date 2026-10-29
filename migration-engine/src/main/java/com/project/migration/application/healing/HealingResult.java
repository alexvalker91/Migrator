package com.project.migration.application.healing;

import com.project.migration.domain.model.Checks;
import com.project.migration.domain.model.code.CodeChange;

import java.util.List;
import java.util.Optional;

/**
 * Result of one attempt.
 *
 * @param reasoning     reasoning text of the proposal, when the model returned it
 * @param appliedChanges the batch now in the working tree (empty unless {@link HealingStatus#APPLIED})
 */
public record HealingResult(
        HealingStatus status,
        String diagnosis,
        Optional<String> reasoning,
        String changeSummary,
        List<CodeChange> appliedChanges) {

    public HealingResult {
        Checks.notNull(status, "status");
        Checks.notBlank(diagnosis, "diagnosis");
        reasoning = reasoning == null ? Optional.empty() : reasoning;
        Checks.notBlank(changeSummary, "changeSummary");
        appliedChanges = List.copyOf(Checks.notNull(appliedChanges, "appliedChanges"));
    }
}
