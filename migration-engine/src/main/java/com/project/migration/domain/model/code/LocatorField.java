package com.project.migration.domain.model.code;

import com.project.migration.domain.model.Checks;

import java.util.Optional;

/**
 * Locator field as found in source: the Android annotation expression and the iOS one, if already present.
 *
 * @param androidLocator  source form of the Android locator, e.g. {@code accessibility = "input-email"}
 * @param existingIosLocator source form of an existing {@code @iOSXCUITFindBy}, if any
 */
public record LocatorField(FieldRef field, String androidLocator, Optional<String> existingIosLocator) {

    public LocatorField {
        Checks.notNull(field, "field");
        Checks.notBlank(androidLocator, "androidLocator");
        existingIosLocator = existingIosLocator == null ? Optional.empty() : existingIosLocator;
    }

    public boolean hasIosLocator() {
        return existingIosLocator.isPresent();
    }
}
