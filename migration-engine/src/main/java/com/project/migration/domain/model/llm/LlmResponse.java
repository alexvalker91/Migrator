package com.project.migration.domain.model.llm;

import com.project.migration.domain.model.Checks;
import com.project.migration.domain.model.telemetry.TokenUsage;

import java.time.Duration;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * Provider-neutral LLM response. {@code content} is empty when the model answered only with tool calls.
 *
 * @param reasoning reasoning / thinking text, when the deployment returned it
 * @param modelId   deployment that actually served the request (after reasoning fallback)
 */
public record LlmResponse<T>(
        Optional<T> content,
        Optional<String> reasoning,
        List<ToolCall> toolCalls,
        TokenUsage usage,
        String modelId,
        Duration latency) {

    public LlmResponse {
        content = content == null ? Optional.empty() : content;
        reasoning = reasoning == null ? Optional.empty() : reasoning.filter(r -> !r.isBlank());
        toolCalls = List.copyOf(Checks.notNull(toolCalls, "toolCalls"));
        if (content.isEmpty() && toolCalls.isEmpty()) {
            throw new IllegalArgumentException("response needs content or tool calls");
        }
        Checks.notNull(usage, "usage");
        Checks.notBlank(modelId, "modelId");
        Checks.notNull(latency, "latency");
        if (latency.isNegative()) {
            throw new IllegalArgumentException("latency must not be negative");
        }
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }

    public T requireContent() {
        return content.orElseThrow(() -> new NoSuchElementException(
                "model " + modelId + " returned tool calls instead of content: " + toolCalls));
    }
}
