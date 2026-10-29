package com.project.migration.application.orchestrator;

import com.project.migration.domain.model.orchestration.ClassMigrationOutcome;
import com.project.migration.domain.model.orchestration.TestClassMigrationRequest;

/**
 * Migrates one test class end to end, as the command line sees the orchestrator.
 */
public interface ClassMigrator {

    ClassMigrationOutcome migrate(TestClassMigrationRequest request);
}
