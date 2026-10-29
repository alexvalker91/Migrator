package com.project.migration.domain.exception;

/**
 * Maven/TestNG could not be started or its results could not be collected at all.
 */
public final class TestRunnerException extends MigrationException {

    public TestRunnerException(String message) {
        super(message);
    }

    public TestRunnerException(String message, Throwable cause) {
        super(message, cause);
    }
}
