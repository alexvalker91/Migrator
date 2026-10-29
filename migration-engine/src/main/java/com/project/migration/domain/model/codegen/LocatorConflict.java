package com.project.migration.domain.model.codegen;

import com.project.migration.domain.model.Checks;
import com.project.migration.domain.model.code.FieldRef;
import com.project.migration.domain.model.screen.ScreenStateKey;

/**
 * The single iOS locator chosen for a field does not find exactly its element in another state of the screen
 * where the Android locator finds it.
 */
public record LocatorConflict(FieldRef field, ScreenStateKey chosenIn, ScreenStateKey conflictsIn, String detail) {

    public LocatorConflict {
        Checks.notNull(field, "field");
        Checks.notNull(chosenIn, "chosenIn");
        Checks.notNull(conflictsIn, "conflictsIn");
        Checks.notBlank(detail, "detail");
    }
}
