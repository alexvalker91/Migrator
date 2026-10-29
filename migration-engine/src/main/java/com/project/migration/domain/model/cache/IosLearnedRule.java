package com.project.migration.domain.model.cache;

import com.project.migration.domain.model.Checks;
import com.project.migration.domain.model.code.TestMethodRef;

import java.time.Instant;
import java.util.Optional;

/**
 * Behaviour of the iOS app discovered during migration (e.g. "amounts use U+00A0 before the currency"),
 * fed into later prompts.
 *
 * @param id stable slug used for de-duplication
 */
public record IosLearnedRule(String id, String description, Optional<TestMethodRef> learnedFrom, Instant learnedAt) {

    public IosLearnedRule {
        Checks.notBlank(id, "id");
        Checks.notBlank(description, "description");
        learnedFrom = learnedFrom == null ? Optional.empty() : learnedFrom;
        Checks.notNull(learnedAt, "learnedAt");
    }
}
