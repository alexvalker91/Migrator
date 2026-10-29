package com.project.migration.application.snapshot;

import com.project.migration.domain.model.analysis.MigrationTaskDelta;
import com.project.migration.domain.model.snapshot.SnapshotCollectionResult;

/**
 * Stage 2 as the orchestrator sees it.
 */
public interface SnapshotCollector {

    SnapshotCollectionResult collect(MigrationTaskDelta delta);
}
