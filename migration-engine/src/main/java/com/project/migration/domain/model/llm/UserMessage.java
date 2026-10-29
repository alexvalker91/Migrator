package com.project.migration.domain.model.llm;

import com.project.migration.domain.model.Checks;
import com.project.migration.domain.model.media.ImageAttachment;

import java.util.List;

/**
 * User turn; may carry screenshots for vision models.
 */
public record UserMessage(String text, List<ImageAttachment> images) implements LlmMessage {

    public UserMessage {
        Checks.notBlank(text, "text");
        images = List.copyOf(Checks.notNull(images, "images"));
    }

    public static UserMessage of(String text) {
        return new UserMessage(text, List.of());
    }

    public static UserMessage withImages(String text, ImageAttachment... images) {
        return new UserMessage(text, List.of(images));
    }
}
