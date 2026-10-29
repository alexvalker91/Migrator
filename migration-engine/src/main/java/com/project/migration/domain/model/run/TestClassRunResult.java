package com.project.migration.domain.model.run;

import com.project.migration.domain.model.Checks;
import com.project.migration.domain.model.Platform;

import java.time.Duration;
import java.util.List;

/**
 * Result of running a whole test class once (Stage 5 control run / Android regression guard).
 */
public record TestClassRunResult(String testClassFqcn, Platform platform, List<TestRunResult> results, Duration duration) {

    public TestClassRunResult {
        Checks.notBlank(testClassFqcn, "testClassFqcn");
        Checks.notNull(platform, "platform");
        results = List.copyOf(Checks.notNull(results, "results"));
        Checks.notNull(duration, "duration");
    }

    public boolean allPassed() {
        return !results.isEmpty() && results.stream().allMatch(TestRunResult::isPassed);
    }

    public long count(TestOutcome outcome) {
        return results.stream().filter(r -> r.outcome() == outcome).count();
    }
}
