package com.project.migration.domain.exception;

/**
 * An AST change could not be applied (target not found, generated code does not parse); no file was written.
 */
public final class CodeModificationException extends MigrationException {

    public CodeModificationException(String message) {
        super(message);
    }

    public CodeModificationException(String message, Throwable cause) {
        super(message, cause);
    }
}
