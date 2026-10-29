package com.project.migration.application.stability;

/**
 * Why a test did not get through the gate.
 */
public enum GateFailureKind {
    /** The test passed three times. */
    NONE,
    /** Healing attempts used up, the test still fails. */
    EXHAUSTED,
    /** The time or token budget of the test ran out. */
    BUDGET,
    /** The cause is in a shared framework class. */
    SHARED_FRAMEWORK,
    /** The test data is used up; the code is fine. */
    DATA_EXHAUSTED,
    /** Runs ended SKIPPED/ERROR again and again: device, session or network. */
    ENVIRONMENT
}
