package com.project.migration.domain.model.codegen;

import com.project.migration.domain.model.Checks;
import com.project.migration.domain.model.code.FieldRef;

/**
 * A locator field that got no iOS annotation; the code keeps working on Android and the field is reported.
 */
public record UnresolvedLocator(FieldRef field, String reason) {

    public UnresolvedLocator {
        Checks.notNull(field, "field");
        Checks.notBlank(reason, "reason");
    }
}
