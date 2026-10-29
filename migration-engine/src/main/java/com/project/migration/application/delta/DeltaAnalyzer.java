package com.project.migration.application.delta;

import com.project.migration.domain.model.analysis.MigrationTaskDelta;

/**
 * Stage 1 as the orchestrator sees it.
 */
public interface DeltaAnalyzer {

    MigrationTaskDelta analyze(String testClassFqcn);
}
