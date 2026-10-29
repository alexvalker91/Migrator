package com.project.migration.domain.model.screen;

import com.project.migration.domain.model.Checks;

/**
 * Logical screen identifier: the simple class name of the screen's Page Object (e.g. {@code LoginPage}).
 * Stage 1 must fail on simple-name collisions across packages instead of silently merging cache entries.
 */
public record ScreenId(String value) {

    public ScreenId {
        Checks.notBlank(value, "screen id");
        if (!SafeName.isSafe(value)) {
            throw new IllegalArgumentException("screen id must be a file-system safe name: " + value);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
