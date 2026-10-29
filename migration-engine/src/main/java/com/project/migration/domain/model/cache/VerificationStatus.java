package com.project.migration.domain.model.cache;

/**
 * Verification state of a cached artifact (locator mapping, step, screen state).
 */
public enum VerificationStatus {
    /** Generated but not yet confirmed by a 3/3 PASS run. */
    UNVERIFIED,
    /** Confirmed by a 3/3 PASS run on iOS; valid until the end of the migration (code & app freeze). */
    VERIFIED_STABLE,
    /** Was used by a test that ended as NEEDS_MANUAL_FIX. */
    FAILED
}
