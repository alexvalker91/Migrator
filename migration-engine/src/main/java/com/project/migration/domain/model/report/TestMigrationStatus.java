package com.project.migration.domain.model.report;

/**
 * Migration outcome of a single {@code @Test} method.
 */
public enum TestMigrationStatus {
    PENDING(false),
    IN_PROGRESS(false),
    /** 3/3 PASS without self-healing. */
    STABLE_PASS(true),
    /** 3/3 PASS after at least one self-healing attempt. */
    HEALED_AND_PASSED(true),
    /** Self-healing exhausted; code rolled back and the test tagged {@code ios-needs-fix}. */
    NEEDS_MANUAL_FIX(true),
    /** {@code android-only}: no iOS counterpart (e.g. Google Wallet); tagged instead of migrated. */
    PLATFORM_SPECIFIC(true),
    /** Already migrated and verified in a previous run. */
    SKIPPED_ALREADY_VERIFIED(true);

    private final boolean terminal;

    TestMigrationStatus(boolean terminal) {
        this.terminal = terminal;
    }

    public boolean isTerminal() {
        return terminal;
    }

    /** Counts as migrated in project-wide progress. */
    public boolean isMigrated() {
        return this == STABLE_PASS || this == HEALED_AND_PASSED || this == PLATFORM_SPECIFIC
                || this == SKIPPED_ALREADY_VERIFIED;
    }
}
