package com.project.migration.domain.model;

/**
 * Stages of the class migration pipeline (see ARCHITECTURE_SPEC.md, section 3).
 */
public enum MigrationStage {
    STAGE_0_GLOBALS("Global components"),
    STAGE_1_DELTA_ANALYSIS("Delta analysis"),
    STAGE_2_SNAPSHOTS("Dual-MCP snapshots"),
    STAGE_3_CODEGEN("Code generation"),
    STAGE_4_STABILIZATION("Stabilization 3/3"),
    STAGE_4_SELF_HEALING("Self-healing"),
    STAGE_5_FINALIZE("Final validation & report");

    private final String displayName;

    MigrationStage(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
