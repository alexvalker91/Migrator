package com.project.migration.domain.port;

import com.project.migration.domain.model.report.ClassMigrationReport;
import com.project.migration.domain.model.report.ReportArtifacts;

/**
 * Renders {@code migration_reports/<TestClass>/dashboard.html} and {@code report.md}.
 */
public interface ReportGeneratorPort {

    ReportArtifacts generate(ClassMigrationReport report);
}
