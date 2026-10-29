package com.project.migration.domain.model.llm;

import com.project.migration.domain.model.Checks;

public record SystemMessage(String text) implements LlmMessage {

    public SystemMessage {
        Checks.notBlank(text, "text");
    }
}
