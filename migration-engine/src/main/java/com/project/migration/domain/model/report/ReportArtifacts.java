package com.project.migration.domain.model.report;

import com.project.migration.domain.model.Checks;

import java.nio.file.Path;

/**
 * Files produced by the report generator.
 */
public record ReportArtifacts(Path dashboardHtml, Path reportMarkdown) {

    public ReportArtifacts {
        Checks.notNull(dashboardHtml, "dashboardHtml");
        Checks.notNull(reportMarkdown, "reportMarkdown");
    }
}
