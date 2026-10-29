package com.project.migration.application.healing;

/**
 * Outcome of one self-healing attempt.
 */
public enum HealingStatus {
    /** Changes are applied and compile; the gate runs the test again. */
    APPLIED,
    /** The cause is exhausted test data; nothing was changed. */
    DATA_EXHAUSTED,
    /** The cause is in a shared framework class; nothing was changed. */
    SHARED_FRAMEWORK,
    /** The cause is the environment; nothing was changed. */
    ENVIRONMENT,
    /** The model proposed nothing that passes validation, even after a repair round. */
    NO_VALID_CHANGES,
    /** The changes did not compile and were reverted. */
    COMPILATION_FAILED
}
