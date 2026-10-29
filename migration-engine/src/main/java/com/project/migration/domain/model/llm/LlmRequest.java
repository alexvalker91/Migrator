package com.project.migration.domain.model.llm;

import com.project.migration.domain.model.Checks;
import com.project.migration.domain.model.MigrationStage;

import java.util.ArrayList;
import java.util.List;

/**
 * Provider-neutral LLM request.
 *
 * @param purpose            short label for telemetry and logs, e.g. {@code locator-mapping:LoginPage}
 * @param responseType       {@code String.class} for free text; a {@code record} type for structured output
 *                           (the adapter derives the JSON schema and deserializes the answer)
 * @param reasoningRequested ask the router for the {@code -reasoning}/{@code -with-thinking} deployment of the tier
 */
public record LlmRequest<T>(
        LlmTier tier,
        MigrationStage stage,
        String purpose,
        List<LlmMessage> messages,
        List<ToolSpec> tools,
        Class<T> responseType,
        boolean reasoningRequested) {

    public LlmRequest {
        Checks.notNull(tier, "tier");
        Checks.notNull(stage, "stage");
        Checks.notBlank(purpose, "purpose");
        messages = List.copyOf(Checks.notNull(messages, "messages"));
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("messages must not be empty");
        }
        if (messages.stream().noneMatch(m -> m instanceof UserMessage || m instanceof ToolResultMessage)) {
            throw new IllegalArgumentException("conversation must contain a user or tool-result message");
        }
        tools = List.copyOf(Checks.notNull(tools, "tools"));
        if (tools.stream().map(ToolSpec::name).distinct().count() != tools.size()) {
            throw new IllegalArgumentException("tool names must be unique");
        }
        Checks.notNull(responseType, "responseType");
        if (responseType != String.class && !responseType.isRecord()) {
            throw new IllegalArgumentException("responseType must be String or a record, was " + responseType.getName());
        }
    }

    public static LlmRequest<String> text(LlmTier tier, MigrationStage stage, String purpose, List<LlmMessage> messages) {
        return new LlmRequest<>(tier, stage, purpose, messages, List.of(), String.class, false);
    }

    public static <R> LlmRequest<R> structured(
            LlmTier tier, MigrationStage stage, String purpose, List<LlmMessage> messages, Class<R> responseType) {
        return new LlmRequest<>(tier, stage, purpose, messages, List.of(), responseType, false);
    }

    public LlmRequest<T> withTools(List<ToolSpec> newTools) {
        return new LlmRequest<>(tier, stage, purpose, messages, newTools, responseType, reasoningRequested);
    }

    public LlmRequest<T> withReasoning(boolean requested) {
        return new LlmRequest<>(tier, stage, purpose, messages, tools, responseType, requested);
    }

    /** Next turn of a tool-calling loop: same settings, conversation extended by {@code appended}. */
    public LlmRequest<T> continuedWith(List<? extends LlmMessage> appended) {
        List<LlmMessage> extended = new ArrayList<>(messages);
        extended.addAll(appended);
        return new LlmRequest<>(tier, stage, purpose, extended, tools, responseType, reasoningRequested);
    }

    public boolean hasImages() {
        return messages.stream().anyMatch(m -> m instanceof UserMessage user && !user.images().isEmpty());
    }

    public boolean isStructured() {
        return responseType != String.class;
    }
}
