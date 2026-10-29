package com.project.migration.domain.model.mcp;

import com.project.migration.domain.model.Checks;

/**
 * A tool invocation the engine itself performs (not the LLM), e.g. to open the Appium session.
 *
 * @param argumentsJson JSON object with the arguments; {@code {}} for none
 */
public record McpToolCall(String tool, String argumentsJson) {

    public McpToolCall {
        Checks.notBlank(tool, "tool");
        Checks.notBlank(argumentsJson, "argumentsJson");
    }
}
