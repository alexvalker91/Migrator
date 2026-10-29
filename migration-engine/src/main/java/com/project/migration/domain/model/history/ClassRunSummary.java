package com.project.migration.domain.model.history;

import com.project.migration.domain.model.Checks;
import com.project.migration.domain.model.orchestration.MigrationExitStatus;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

/**
 * What one finished class migration cost: the data the scaling projection of later reports is built from.
 *
 * @param processedTests   tests the engine worked on (stable + healed + needing a manual fix); the unit of cost per test
 * @param healingAttempts  self-healing attempts over all tests
 * @param totalTokens      input + output + reasoning tokens of all LLM calls
 * @param cost             price of those tokens in dollars
 * @param savedTokens      tokens avoided by XML cleaning and the cache
 * @param runsDuration     wall-clock time of the 3/3 runs
 * @param healingDuration  wall-clock time of self-healing
 */
public record ClassRunSummary(
        String testClassFqcn,
        Instant finishedAt,
        MigrationExitStatus status,
        int processedTests,
        int stableTests,
        int healedTests,
        int manualFixTests,
        int healingAttempts,
        long totalTokens,
        BigDecimal cost,
        long savedTokens,
        BigDecimal savedCost,
        double cacheHitRate,
        Duration duration,
        Duration runsDuration,
        Duration healingDuration) {

    public ClassRunSummary {
        Checks.notBlank(testClassFqcn, "testClassFqcn");
        Checks.notNull(finishedAt, "finishedAt");
        Checks.notNull(status, "status");
        Checks.nonNegative(processedTests, "processedTests");
        Checks.nonNegative(stableTests, "stableTests");
        Checks.nonNegative(healedTests, "healedTests");
        Checks.nonNegative(manualFixTests, "manualFixTests");
        Checks.nonNegative(healingAttempts, "healingAttempts");
        Checks.nonNegative(totalTokens, "totalTokens");
        Checks.notNull(cost, "cost");
        Checks.nonNegative(savedTokens, "savedTokens");
        Checks.notNull(savedCost, "savedCost");
        if (cacheHitRate < 0 || cacheHitRate > 1) {
            throw new IllegalArgumentException("cacheHitRate must be within 0..1, was " + cacheHitRate);
        }
        Checks.notNull(duration, "duration");
        Checks.notNull(runsDuration, "runsDuration");
        Checks.notNull(healingDuration, "healingDuration");
    }

    /** Dollars per processed test; {@code 0} when the engine did no per-test work. */
    public BigDecimal costPerTest() {
        return processedTests == 0 ? BigDecimal.ZERO : cost.divide(BigDecimal.valueOf(processedTests), 6, java.math.RoundingMode.HALF_UP);
    }

    public double tokensPerTest() {
        return processedTests == 0 ? 0.0 : (double) totalTokens / processedTests;
    }

    public Duration durationPerTest() {
        return processedTests == 0 ? Duration.ZERO : duration.dividedBy(processedTests);
    }
}
