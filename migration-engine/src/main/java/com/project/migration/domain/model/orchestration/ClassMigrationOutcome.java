package com.project.migration.domain.model.orchestration;

import com.project.migration.domain.model.Checks;
import com.project.migration.domain.model.report.ClassMigrationReport;
import com.project.migration.domain.model.report.ReportArtifacts;

import java.util.Optional;

/**
 * Final result of a class migration.
 *
 * @param artifacts files of the report generator
 * @param pushed    the migration branch was pushed
 * @param message   one line for the console: why the migration ended like this
 */
public record ClassMigrationOutcome(
        MigrationExitStatus status,
        ClassMigrationReport report,
        Optional<ReportArtifacts> artifacts,
        boolean pushed,
        String message) {

    public ClassMigrationOutcome {
        Checks.notNull(status, "status");
        Checks.notNull(report, "report");
        artifacts = artifacts == null ? Optional.empty() : artifacts;
        Checks.notNull(message, "message");
    }
}
