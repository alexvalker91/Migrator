package com.project.migration.domain.model.llm;

import com.project.migration.domain.model.Checks;

import java.util.List;
import java.util.Optional;

/**
 * Previous model turn replayed into the conversation (text and/or tool calls).
 */
public record AssistantMessage(Optional<String> text, List<ToolCall> toolCalls) implements LlmMessage {

    public AssistantMessage {
        text = text == null ? Optional.empty() : text.filter(t -> !t.isBlank());
        toolCalls = List.copyOf(Checks.notNull(toolCalls, "toolCalls"));
        if (text.isEmpty() && toolCalls.isEmpty()) {
            throw new IllegalArgumentException("assistant message needs text or tool calls");
        }
    }

    public static AssistantMessage of(String text) {
        return new AssistantMessage(Optional.of(text), List.of());
    }

    public static AssistantMessage ofToolCalls(List<ToolCall> toolCalls) {
        return new AssistantMessage(Optional.empty(), toolCalls);
    }
}
