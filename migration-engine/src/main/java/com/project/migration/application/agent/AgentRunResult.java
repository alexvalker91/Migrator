package com.project.migration.application.agent;

import com.project.migration.domain.model.Checks;
import com.project.migration.domain.model.agent.AgentRunOutcome;

import java.util.List;

/**
 * How a run ended and what it did.
 *
 * @param steps      tool-calling rounds
 * @param tokens     tokens spent by the model in the run
 * @param finalText  the model's last words (empty when the run was cut off)
 */
public record AgentRunResult(AgentRunOutcome outcome, int steps, long tokens, String finalText, List<ToolTrace> trace) {

    public AgentRunResult {
        Checks.notNull(outcome, "outcome");
        Checks.nonNegative(steps, "steps");
        Checks.nonNegative(tokens, "tokens");
        Checks.notNull(finalText, "finalText");
        trace = List.copyOf(Checks.notNull(trace, "trace"));
    }
}
