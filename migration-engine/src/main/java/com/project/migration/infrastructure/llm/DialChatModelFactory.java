package com.project.migration.infrastructure.llm;

import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

import java.time.Duration;
import java.util.Map;

/**
 * {@link OpenAiChatModel} per DIAL deployment, following the connection contract of CLAUDE.md (section 3):
 * deployment base URL, {@code api-key} header, temperature 0, request/response logging (routed by
 * {@link LlmWireLog}), 120 s timeout. Adds retries of transient failures (429/5xx/timeouts), strict JSON-schema
 * output and returned thinking for reasoning deployments.
 */
public final class DialChatModelFactory implements ChatModelFactory {

    static final Duration TIMEOUT = Duration.ofSeconds(120);
    static final int MAX_RETRIES = 3;

    private final DialSettings settings;

    public DialChatModelFactory(DialSettings settings) {
        this.settings = settings;
    }

    @Override
    public ChatModel create(String modelId) {
        return OpenAiChatModel.builder()
                .baseUrl(settings.deploymentUrl(modelId))
                .apiKey(settings.apiKey())
                .customHeaders(Map.of("api-key", settings.apiKey()))
                .modelName(modelId)
                .temperature(0.0)
                .logRequests(true)
                .logResponses(true)
                .timeout(TIMEOUT)
                .maxRetries(MAX_RETRIES)
                .supportedCapabilities(Capability.RESPONSE_FORMAT_JSON_SCHEMA)
                .strictJsonSchema(true)
                .returnThinking(true)
                .build();
    }
}
