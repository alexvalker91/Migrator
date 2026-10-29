package com.project.migration.domain.model.vcs;

import com.project.migration.domain.model.code.CodeNames;

/**
 * Naming conventions for migration branches and commits.
 */
public final class BranchNames {

    private BranchNames() {
    }

    /** {@code migrate/ios-LoginTest} */
    public static String migrationBranch(String testClassFqcn) {
        return "migrate/ios-" + CodeNames.simpleName(testClassFqcn);
    }

    /** Baseline commit after Stage 3 so self-healing rollbacks never drop not-yet-verified codegen. */
    public static String codegenCommitMessage(String testClassFqcn) {
        return "codegen: " + CodeNames.simpleName(testClassFqcn);
    }

    /** Commit that fixes the {@code ios-needs-fix} tag of a test the engine gave up on. */
    public static String isolationCommitMessage(String testMethodName) {
        return "isolate: " + testMethodName + " -> ios-needs-fix";
    }

    public static String checkpointCommitMessage(String testMethodName, int stabilityRuns) {
        return "checkpoint: " + testMethodName + " passed " + stabilityRuns + "/" + stabilityRuns + " on iOS";
    }
}
