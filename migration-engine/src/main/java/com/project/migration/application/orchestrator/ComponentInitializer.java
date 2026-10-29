package com.project.migration.application.orchestrator;

import com.project.migration.domain.model.analysis.MigrationTaskDelta;

/**
 * Stage 0 as the orchestrator sees it.
 */
public interface ComponentInitializer {

    ComponentInitResult initialize(MigrationTaskDelta delta);
}
