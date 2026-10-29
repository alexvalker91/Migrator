package com.project.migration.domain.model.orchestration;

/**
 * How a class migration ended, mapped to the process exit code by the CLI.
 */
public enum MigrationExitStatus {
    /** Every test migrated, final iOS run and Android regression guard are green. */
    SUCCESS(0),
    /** Some tests need a manual fix (isolated with {@code ios-needs-fix}); the rest is green. */
    COMPLETED_WITH_MANUAL_FIX(1),
    /** The final iOS class run or the Android regression guard is red. */
    STAGE5_FAILED(2),
    /** The migration could not run to the end: dirty tree, uncompilable codegen, environment down. */
    ABORTED(3);

    private final int exitCode;

    MigrationExitStatus(int exitCode) {
        this.exitCode = exitCode;
    }

    public int exitCode() {
        return exitCode;
    }
}
