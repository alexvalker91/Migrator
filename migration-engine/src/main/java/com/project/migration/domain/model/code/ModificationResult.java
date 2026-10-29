package com.project.migration.domain.model.code;

import com.project.migration.domain.model.Checks;

import java.nio.file.Path;
import java.util.List;

/**
 * Files actually rewritten by a batch of {@link CodeChange}s (changes that turned out to be no-ops are absent).
 */
public record ModificationResult(List<Path> changedFiles, int appliedChanges) {

    public ModificationResult {
        changedFiles = List.copyOf(Checks.notNull(changedFiles, "changedFiles"));
        Checks.nonNegative(appliedChanges, "appliedChanges");
    }

    public boolean hasChanges() {
        return !changedFiles.isEmpty();
    }
}
