package com.project.migration.domain.model.mcp;

import com.project.migration.domain.model.Checks;
import com.project.migration.domain.model.media.ImageAttachment;

import java.util.List;

/**
 * Result of an MCP tool invocation. {@code isError = true} is a tool-level failure (element not found, ...)
 * reported back to the agent, not a transport failure.
 */
public record McpToolResult(String text, List<ImageAttachment> images, boolean isError) {

    public McpToolResult {
        Checks.notNull(text, "text");
        images = List.copyOf(Checks.notNull(images, "images"));
    }

    public static McpToolResult success(String text) {
        return new McpToolResult(text, List.of(), false);
    }

    public static McpToolResult error(String text) {
        return new McpToolResult(text, List.of(), true);
    }
}
