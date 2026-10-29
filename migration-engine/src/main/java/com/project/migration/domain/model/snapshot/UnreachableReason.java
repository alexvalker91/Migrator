package com.project.migration.domain.model.snapshot;

/**
 * Why a screen state could not be captured.
 */
public enum UnreachableReason {
    /** The agent ran out of steps or tokens. */
    BUDGET_EXHAUSTED,
    /** The agent made no progress for too long. */
    STUCK,
    /** The agent finished without reaching the state. */
    NOT_REACHED,
    /** The MCP session failed. */
    MCP_ERROR,
    /** The reference platform did not reach the state, so there is nothing to pair the other side with. */
    NO_REFERENCE
}
