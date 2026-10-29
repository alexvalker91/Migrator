package com.project.migration.domain.model.code;

import com.project.migration.domain.model.Checks;

/**
 * Adds {@code @iOSXCUITFindBy(<strategy> = "<value>")} above the existing {@code @AndroidFindBy} of a field.
 * If the field already has an iOS locator it is replaced.
 */
public record AddIosLocator(FieldRef field, IosLocatorStrategy strategy, String value) implements CodeChange {

    public AddIosLocator {
        Checks.notNull(field, "field");
        Checks.notNull(strategy, "strategy");
        Checks.notBlank(value, "value");
    }

    @Override
    public String targetClassFqcn() {
        return field.classFqcn();
    }
}
