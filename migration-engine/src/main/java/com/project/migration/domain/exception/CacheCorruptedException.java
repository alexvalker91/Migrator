package com.project.migration.domain.exception;

/**
 * A cache or snapshot file exists but cannot be read or parsed; never silently replaced.
 */
public final class CacheCorruptedException extends MigrationException {

    public CacheCorruptedException(String message) {
        super(message);
    }

    public CacheCorruptedException(String message, Throwable cause) {
        super(message, cause);
    }
}
