package com.project.migration.domain.model.telemetry;

import com.project.migration.domain.model.Checks;

/**
 * Estimated tokens not sent to an LLM thanks to {@code source}.
 */
public record TokenSavings(SavingsSource source, long tokens) {

    public TokenSavings {
        Checks.notNull(source, "source");
        Checks.nonNegative(tokens, "tokens");
    }
}
