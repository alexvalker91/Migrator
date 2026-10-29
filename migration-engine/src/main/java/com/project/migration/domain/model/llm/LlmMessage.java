package com.project.migration.domain.model.llm;

/**
 * One message of an LLM conversation.
 */
public sealed interface LlmMessage permits SystemMessage, UserMessage, AssistantMessage, ToolResultMessage {
}
