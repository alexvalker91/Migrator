package com.project.migration.application.agent;

import com.project.migration.domain.model.Checks;

/**
 * What a tool call gives back to the model.
 *
 * @param progress the call moved the task forward (e.g. a snapshot was stored); resets the no-progress counter
 * @param isError  the call failed at tool level; the model sees it and may retry differently
 * @param pageSource the content is a cleaned screen XML; old ones are dropped from the conversation to save tokens
 */
public record ToolOutcome(String content, boolean progress, boolean isError, boolean pageSource) {

    public ToolOutcome {
        Checks.notNull(content, "content");
    }

    public static ToolOutcome text(String content) {
        return new ToolOutcome(content, false, false, false);
    }

    public static ToolOutcome progress(String content) {
        return new ToolOutcome(content, true, false, false);
    }

    public static ToolOutcome error(String content) {
        return new ToolOutcome(content, false, true, false);
    }

    public static ToolOutcome screen(String cleanXml) {
        return new ToolOutcome(cleanXml, false, false, true);
    }
}
