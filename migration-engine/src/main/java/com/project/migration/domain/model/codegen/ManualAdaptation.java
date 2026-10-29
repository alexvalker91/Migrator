package com.project.migration.domain.model.codegen;

import com.project.migration.domain.model.Checks;
import com.project.migration.domain.model.code.MethodRef;

/**
 * A method with Android-specific logic whose adaptation was rejected after a repair attempt; it stays as it is.
 */
public record ManualAdaptation(MethodRef method, String reason) {

    public ManualAdaptation {
        Checks.notNull(method, "method");
        Checks.notBlank(reason, "reason");
    }
}
