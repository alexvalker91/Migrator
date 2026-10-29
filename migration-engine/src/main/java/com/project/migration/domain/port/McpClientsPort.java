package com.project.migration.domain.port;

import com.project.migration.domain.exception.McpException;
import com.project.migration.domain.model.Platform;

/**
 * Access to the Android and iOS MCP sessions used side by side in Stage 2 and self-healing.
 */
public interface McpClientsPort extends AutoCloseable {

    /**
     * Lazily opens the session on first use and keeps it for subsequent calls.
     *
     * @throws McpException when the session cannot be opened
     */
    McpClientPort forPlatform(Platform platform);

    /** Closes every opened session. Idempotent. */
    @Override
    void close();
}
