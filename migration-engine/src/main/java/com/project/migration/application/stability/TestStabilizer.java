package com.project.migration.application.stability;

/**
 * Stage 4 for one test as the orchestrator sees it.
 */
public interface TestStabilizer {

    GateOutcome stabilize(GateRequest request);
}
