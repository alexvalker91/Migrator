package com.project.migration.domain.model.llm;

import com.project.migration.domain.model.Checks;

/**
 * Tool invocation requested by the model.
 *
 * @param argumentsJson raw JSON object with the arguments
 */
public record ToolCall(String id, String name, String argumentsJson) {

    public ToolCall {
        Checks.notBlank(id, "id");
        Checks.notBlank(name, "name");
        Checks.notNull(argumentsJson, "argumentsJson");
    }
}
