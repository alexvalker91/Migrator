package com.project.migration.domain.model.code;

import com.project.migration.domain.model.Checks;

/**
 * Inserts a statement (e.g. {@code scrollToElementByText("Save");}) right before the first statement of
 * {@code method} that matches {@code anchorStatement} (compared after AST normalisation).
 */
public record InsertCallBefore(MethodRef method, String anchorStatement, String statement) implements CodeChange {

    public InsertCallBefore {
        Checks.notNull(method, "method");
        Checks.notBlank(anchorStatement, "anchorStatement");
        Checks.notBlank(statement, "statement");
    }

    @Override
    public String targetClassFqcn() {
        return method.classFqcn();
    }
}
