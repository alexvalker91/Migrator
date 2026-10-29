package com.project.migration.domain.model.report;

import com.project.migration.domain.model.Checks;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Final failure dossier of a test that needs a manual fix. Artifacts are referenced by path, not embedded.
 *
 * @param verdict technical verdict of the Tier-1 model
 */
public record FailureEvidence(String stackTrace, Optional<Path> screenshot, Optional<Path> cleanXml, String verdict) {

    public FailureEvidence {
        Checks.notNull(stackTrace, "stackTrace");
        screenshot = screenshot == null ? Optional.empty() : screenshot;
        cleanXml = cleanXml == null ? Optional.empty() : cleanXml;
        Checks.notBlank(verdict, "verdict");
    }
}
