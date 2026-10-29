package com.project.migration.application.agent;

import com.project.migration.domain.model.Checks;
import com.project.migration.domain.model.llm.ToolSpec;

import java.util.function.Function;

/**
 * A tool implemented by the engine itself and offered to the model next to the MCP tools.
 */
public record SyntheticTool(ToolSpec spec, Function<String, ToolOutcome> handler) {

    public SyntheticTool {
        Checks.notNull(spec, "spec");
        Checks.notNull(handler, "handler");
    }
}
