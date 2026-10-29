package com.project.migration.domain.model.code;

/**
 * A single AST-level modification of the target project's source code. Applied by {@code CodeModifierPort};
 * never applied with regular expressions.
 */
public sealed interface CodeChange
        permits AddIosLocator, InsertCallBefore, ReplaceMethodBody, AddTestGroups, AddImport {

    /** Fully-qualified name of the class whose source file is modified. */
    String targetClassFqcn();
}
