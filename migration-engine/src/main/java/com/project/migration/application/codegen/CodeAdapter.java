package com.project.migration.application.codegen;

import com.project.migration.domain.model.analysis.MigrationTaskDelta;
import com.project.migration.domain.model.codegen.CodeAdaptationResult;

/**
 * Stage 3 as the orchestrator sees it.
 */
public interface CodeAdapter {

    CodeAdaptationResult adapt(MigrationTaskDelta delta);
}
