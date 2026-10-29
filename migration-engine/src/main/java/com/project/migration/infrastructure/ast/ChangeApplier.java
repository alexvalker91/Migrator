package com.project.migration.infrastructure.ast;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.project.migration.domain.model.code.CodeChange;

import java.util.List;

/**
 * Turns one kind of {@link CodeChange} into text edits located by the AST of the current file content.
 *
 * @param <T> the change type
 */
interface ChangeApplier<T extends CodeChange> {

    /**
     * @param type the declaration of {@link CodeChange#targetClassFqcn()} inside {@code unit}
     * @return the edits; empty if the change is already in place (no-op)
     * @throws com.project.migration.domain.exception.CodeModificationException if it cannot be applied
     */
    List<SourceText.Edit> edits(CompilationUnit unit, SourceText source, TypeDeclaration<?> type, T change);
}
