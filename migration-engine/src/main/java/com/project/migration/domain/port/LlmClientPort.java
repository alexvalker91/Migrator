package com.project.migration.domain.port;

import com.project.migration.domain.exception.LlmCallException;
import com.project.migration.domain.model.llm.LlmRequest;
import com.project.migration.domain.model.llm.LlmResponse;

/**
 * Tier-routed access to the LLM gateway (EPAM AI DIAL). The adapter picks the deployment for
 * {@code request.tier()} (reasoning variant when {@code reasoningRequested}), maps domain messages and images
 * to the provider format, and for structured requests derives the JSON schema of {@code responseType} and
 * deserializes the answer.
 *
 * <p>One call = one model turn: tool calls are returned, not executed; the agent loop lives in the
 * application layer.</p>
 */
public interface LlmClientPort {

    /**
     * @throws LlmCallException after retries are exhausted, or when structured output cannot be parsed
     */
    <T> LlmResponse<T> chat(LlmRequest<T> request);
}
