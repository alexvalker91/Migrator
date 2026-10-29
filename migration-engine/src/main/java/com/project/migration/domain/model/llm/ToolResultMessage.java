package com.project.migration.domain.model.llm;

import com.project.migration.domain.model.Checks;

/**
 * Result of executing a {@link ToolCall}, sent back to the model.
 */
public record ToolResultMessage(String toolCallId, String toolName, String content) implements LlmMessage {

    public ToolResultMessage {
        Checks.notBlank(toolCallId, "toolCallId");
        Checks.notBlank(toolName, "toolName");
        Checks.notNull(content, "content");
    }
}
