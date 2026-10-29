package com.project.migration.domain.exception;

/**
 * A git command failed (dirty conflict, missing ref, push rejected).
 */
public final class GitOperationException extends MigrationException {

    public GitOperationException(String message) {
        super(message);
    }

    public GitOperationException(String message, Throwable cause) {
        super(message, cause);
    }
}
