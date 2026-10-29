package com.project.migration.domain.model.report;

/**
 * What happened after a self-healing fix was applied.
 */
public enum HealingOutcome {
    /** The fix led to 3/3 PASS. */
    RESOLVED,
    /** The test failed again during the 3/3 gate. */
    STILL_FAILING,
    /** The fix did not compile and was reverted. */
    COMPILATION_FAILED
}
