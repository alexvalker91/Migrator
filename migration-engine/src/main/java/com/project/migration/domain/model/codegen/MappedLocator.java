package com.project.migration.domain.model.codegen;

import com.project.migration.domain.model.Checks;
import com.project.migration.domain.model.code.FieldRef;
import com.project.migration.domain.model.code.IosLocatorStrategy;
import com.project.migration.domain.model.screen.ScreenStateKey;

/**
 * An iOS locator that was validated against the iOS snapshot and applied to the field.
 *
 * @param foundIn state whose snapshot pair the mapping was derived from
 */
public record MappedLocator(FieldRef field, ScreenStateKey foundIn, IosLocatorStrategy strategy, String value) {

    public MappedLocator {
        Checks.notNull(field, "field");
        Checks.notNull(foundIn, "foundIn");
        Checks.notNull(strategy, "strategy");
        Checks.notBlank(value, "value");
    }
}
