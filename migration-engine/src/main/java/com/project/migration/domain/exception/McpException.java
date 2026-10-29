package com.project.migration.domain.exception;

import com.project.migration.domain.model.Platform;

/**
 * MCP transport/session failure (server not started, session lost). Tool-level errors are values
 * ({@code McpToolResult.isError}), not exceptions.
 */
public final class McpException extends MigrationException {

    private final Platform platform;

    public McpException(Platform platform, String message) {
        super("[" + platform + "] " + message);
        this.platform = platform;
    }

    public McpException(Platform platform, String message, Throwable cause) {
        super("[" + platform + "] " + message, cause);
        this.platform = platform;
    }

    public Platform platform() {
        return platform;
    }
}
