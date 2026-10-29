package com.project.migration.application.report;

import com.project.migration.domain.model.report.ClassMigrationReport;

/**
 * Writes the prose of a class report.
 */
public interface ReportNarrator {

    ReportNarrative narrate(ClassMigrationReport report);
}
