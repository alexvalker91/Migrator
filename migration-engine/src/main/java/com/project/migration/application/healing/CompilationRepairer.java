package com.project.migration.application.healing;

import com.project.migration.domain.model.run.CompilationError;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Fixes compilation errors of generated code, as the orchestrator sees it.
 */
public interface CompilationRepairer {

    /**
     * One repair round.
     *
     * @param changedFiles files changed since the migration started (project-relative); the model may edit these
     * @return the change summary when changes were applied, empty when the model produced nothing usable
     */
    java.util.Optional<String> repair(List<CompilationError> errors, Set<Path> changedFiles);
}
