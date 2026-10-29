package com.project.migration.domain.model.analysis;

import com.project.migration.domain.model.Checks;
import com.project.migration.domain.model.code.MethodRef;

/**
 * Occurrence of a platform-specific construct.
 *
 * @param detail short source excerpt or explanation, e.g. {@code new UiScrollable(new UiSelector()...)}
 */
public record ComplexityMarker(ComplexityMarkerType type, MethodRef location, String detail) {

    public ComplexityMarker {
        Checks.notNull(type, "type");
        Checks.notNull(location, "location");
        Checks.notBlank(detail, "detail");
    }
}
