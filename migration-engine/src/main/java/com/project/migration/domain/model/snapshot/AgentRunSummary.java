package com.project.migration.domain.model.snapshot;

import com.project.migration.domain.model.Checks;
import com.project.migration.domain.model.Platform;
import com.project.migration.domain.model.agent.AgentRunOutcome;
import com.project.migration.domain.model.code.TestMethodRef;

/**
 * Outcome of one agent pass over a test on one platform, for the log and the report.
 */
public record AgentRunSummary(TestMethodRef test, Platform platform, AgentRunOutcome outcome, int steps, long tokens) {

    public AgentRunSummary {
        Checks.notNull(test, "test");
        Checks.notNull(platform, "platform");
        Checks.notNull(outcome, "outcome");
        Checks.nonNegative(steps, "steps");
        Checks.nonNegative(tokens, "tokens");
    }
}
