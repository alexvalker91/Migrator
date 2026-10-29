package com.project.migration.domain.model.llm;

import com.project.migration.domain.model.Checks;

/**
 * Tool exposed to the model (typically mirrored from an MCP server).
 *
 * @param parametersJsonSchema JSON Schema of the arguments object, kept as text (no JSON library in domain)
 */
public record ToolSpec(String name, String description, String parametersJsonSchema) {

    public ToolSpec {
        Checks.notBlank(name, "name");
        Checks.notNull(description, "description");
        Checks.notBlank(parametersJsonSchema, "parametersJsonSchema");
    }
}
