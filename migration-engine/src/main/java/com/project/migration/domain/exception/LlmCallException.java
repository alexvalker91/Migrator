package com.project.migration.domain.exception;

/**
 * LLM gateway call failed after retries (network, 4xx/5xx, unparsable structured output).
 */
public final class LlmCallException extends MigrationException {

    public LlmCallException(String message) {
        super(message);
    }

    public LlmCallException(String message, Throwable cause) {
        super(message, cause);
    }
}
