package com.project.migration.domain.model.config;

import com.project.migration.domain.model.Checks;

import java.time.Duration;

/**
 * Safeguards of the per-test stabilization.
 *
 * @param maxTestDuration            wall-clock budget of one test (runs and healing together)
 * @param maxTestTokens              LLM tokens one test may spend
 * @param environmentRetries         repeats of a run that ended SKIPPED/ERROR before the test is given up
 * @param maxConsecutiveEnvironmentFailures tests in a row given up for environment reasons that stop the class
 */
public record GateConfig(
        Duration maxTestDuration, long maxTestTokens, int environmentRetries, int maxConsecutiveEnvironmentFailures) {

    public static final GateConfig DEFAULT = new GateConfig(Duration.ofMinutes(45), 400_000, 2, 3);

    public GateConfig {
        Checks.notNull(maxTestDuration, "maxTestDuration");
        if (maxTestDuration.isZero() || maxTestDuration.isNegative()) {
            throw new IllegalArgumentException("maxTestDuration must be positive");
        }
        if (maxTestTokens <= 0) {
            throw new IllegalArgumentException("maxTestTokens must be > 0");
        }
        Checks.nonNegative(environmentRetries, "environmentRetries");
        Checks.positive(maxConsecutiveEnvironmentFailures, "maxConsecutiveEnvironmentFailures");
    }
}
