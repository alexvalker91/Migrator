package com.project.migration.infrastructure.cli;

/**
 * The command line, the configuration or the environment is wrong; maps to exit code 64.
 */
final class UsageException extends RuntimeException {

    UsageException(String message) {
        super(message);
    }
}
