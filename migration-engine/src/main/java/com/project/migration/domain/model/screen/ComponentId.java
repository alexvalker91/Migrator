package com.project.migration.domain.model.screen;

import com.project.migration.domain.model.Checks;

/**
 * Identifier of a cross-screen global component ({@code TopAppBar}, {@code BottomNavBar},
 * {@code GlobalLoadingSpinner}, {@code SystemNotificationShade}).
 */
public record ComponentId(String value) {

    public ComponentId {
        Checks.notBlank(value, "component id");
        if (!SafeName.isSafe(value)) {
            throw new IllegalArgumentException("component id must be a file-system safe name: " + value);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
