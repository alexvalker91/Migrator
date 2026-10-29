package com.project.migration.domain.model.screen;

import com.project.migration.domain.model.Checks;
import com.project.migration.domain.model.Platform;
import com.project.migration.domain.model.media.ImageAttachment;

import java.time.Instant;
import java.util.Optional;

/**
 * A cleaned XML snapshot (and optional screenshot) of one screen state on one platform.
 */
public record StateSnapshot(
        ScreenStateKey key,
        Platform platform,
        String cleanXml,
        Optional<ImageAttachment> screenshot,
        Instant capturedAt) {

    public StateSnapshot {
        Checks.notNull(key, "key");
        Checks.notNull(platform, "platform");
        Checks.notBlank(cleanXml, "cleanXml");
        screenshot = screenshot == null ? Optional.empty() : screenshot;
        Checks.notNull(capturedAt, "capturedAt");
    }
}
