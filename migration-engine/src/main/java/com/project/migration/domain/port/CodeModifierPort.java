package com.project.migration.domain.port;

import com.project.migration.domain.exception.CodeModificationException;
import com.project.migration.domain.model.code.CodeChange;
import com.project.migration.domain.model.code.ModificationResult;

import java.util.List;

/**
 * AST-based modification of the target project, preserving formatting of untouched code (lexical preserving
 * printer in the adapter).
 */
public interface CodeModifierPort {

    /**
     * Applies all changes or none: every change is validated and applied in memory first, files are written
     * only if the whole batch succeeds.
     *
     * @throws CodeModificationException if any change cannot be applied; nothing is written in that case
     */
    ModificationResult apply(List<CodeChange> changes);
}
