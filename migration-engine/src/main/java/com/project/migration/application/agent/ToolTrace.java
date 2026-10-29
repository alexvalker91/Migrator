package com.project.migration.application.agent;

import com.project.migration.domain.model.Checks;

/**
 * One executed tool call, kept for the walkthrough summary and the log.
 *
 * @param resultHead first characters of the result
 */
public record ToolTrace(String tool, String argumentsJson, String resultHead, boolean error) {

    public ToolTrace {
        Checks.notBlank(tool, "tool");
        Checks.notNull(argumentsJson, "argumentsJson");
        Checks.notNull(resultHead, "resultHead");
    }
}
