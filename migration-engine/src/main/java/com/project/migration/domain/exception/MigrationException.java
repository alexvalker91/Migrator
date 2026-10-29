package com.project.migration.domain.exception;

/**
 * Infrastructure failure the engine cannot treat as a normal outcome. Expected outcomes (failed test,
 * compilation errors, cache miss) are returned as values, never thrown. Adapters retry transient errors
 * themselves and throw only once retries are exhausted.
 */
public abstract sealed class MigrationException extends RuntimeException
        permits LlmCallException, McpException, CacheCorruptedException, GitOperationException,
        TestRunnerException, CodeModificationException {

    protected MigrationException(String message) {
        super(message);
    }

    protected MigrationException(String message, Throwable cause) {
        super(message, cause);
    }
}
