package com.project.migration.domain.model.agent;

/**
 * How a tool-calling run of the model ended. Budget and stuck runs are outcomes, not errors.
 */
public enum AgentRunOutcome {
    /** The model finished on its own with a final answer. */
    COMPLETED,
    /** A step or token limit was reached first. */
    BUDGET_EXHAUSTED,
    /** Too many consecutive rounds without progress. */
    STUCK
}
