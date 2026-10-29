package com.project.migration.application.codegen;

import com.project.migration.domain.exception.CodeModificationException;
import com.project.migration.domain.model.Checks;
import com.project.migration.domain.model.code.CodeChange;
import com.project.migration.domain.model.code.ModificationResult;
import com.project.migration.domain.port.CodeModifierPort;
import com.project.migration.domain.port.MigrationLoggerPort;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Applies a batch of changes: atomically first; if any change is refused nothing was written, so the changes are
 * applied one by one and only the refused ones are reported.
 */
public final class ResilientApplier {

    /** @param failures refused changes with the reason */
    public record Applied(ModificationResult result, Map<CodeChange, String> failures) {
    }

    private final CodeModifierPort modifier;
    private final MigrationLoggerPort logger;

    public ResilientApplier(CodeModifierPort modifier, MigrationLoggerPort logger) {
        this.modifier = Checks.notNull(modifier, "modifier");
        this.logger = Checks.notNull(logger, "logger");
    }

    public Applied apply(List<CodeChange> changes) {
        if (changes.isEmpty()) {
            return new Applied(new ModificationResult(List.of(), 0), Map.of());
        }
        try {
            return new Applied(modifier.apply(changes), Map.of());
        } catch (CodeModificationException batchFailure) {
            logger.warn("Batch of " + changes.size() + " changes refused (" + batchFailure.getMessage()
                    + "); applying them one by one");
        }
        Map<CodeChange, String> failures = new LinkedHashMap<>();
        Set<Path> files = new LinkedHashSet<>();
        int count = 0;
        for (CodeChange change : changes) {
            try {
                ModificationResult single = modifier.apply(List.of(change));
                files.addAll(single.changedFiles());
                count += single.appliedChanges();
            } catch (CodeModificationException e) {
                failures.put(change, e.getMessage());
                logger.warn("Change refused: " + change + ": " + e.getMessage());
            }
        }
        return new Applied(new ModificationResult(List.copyOf(files), count), failures);
    }
}
