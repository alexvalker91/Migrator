package com.project.migration.domain.model.run;

import com.project.migration.domain.model.Checks;
import com.project.migration.domain.model.Platform;
import com.project.migration.domain.model.code.TestMethodRef;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

/**
 * Result of running one {@code @Test} method once. For data-provider tests the run fails if any invocation fails.
 */
public record TestRunResult(
        TestMethodRef test,
        Platform platform,
        TestOutcome outcome,
        Optional<TestFailure> failure,
        Duration duration,
        Optional<Path> testngResultsXml) {

    public TestRunResult {
        Checks.notNull(test, "test");
        Checks.notNull(platform, "platform");
        Checks.notNull(outcome, "outcome");
        failure = failure == null ? Optional.empty() : failure;
        Checks.notNull(duration, "duration");
        testngResultsXml = testngResultsXml == null ? Optional.empty() : testngResultsXml;
        if (outcome == TestOutcome.PASSED && failure.isPresent()) {
            throw new IllegalArgumentException("a passed run must not carry a failure");
        }
        if ((outcome == TestOutcome.FAILED || outcome == TestOutcome.ERROR) && failure.isEmpty()) {
            throw new IllegalArgumentException(outcome + " run must carry failure details");
        }
    }

    public boolean isPassed() {
        return outcome == TestOutcome.PASSED;
    }

    /** Only genuine test failures are healed; SKIPPED/ERROR point to the environment, not to migrated code. */
    public boolean isHealable() {
        return outcome == TestOutcome.FAILED;
    }
}
