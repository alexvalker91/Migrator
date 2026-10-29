package com.project.migration.domain.model.cache;

import com.project.migration.domain.model.Checks;
import com.project.migration.domain.model.code.FieldRef;
import com.project.migration.domain.model.code.IosLocatorStrategy;

/**
 * Android → iOS mapping of one locator field.
 */
public record ScreenElementMapping(
        FieldRef field,
        String androidLocator,
        IosLocatorStrategy iosStrategy,
        String iosLocator,
        VerificationStatus status) {

    public ScreenElementMapping {
        Checks.notNull(field, "field");
        Checks.notBlank(androidLocator, "androidLocator");
        Checks.notNull(iosStrategy, "iosStrategy");
        Checks.notBlank(iosLocator, "iosLocator");
        Checks.notNull(status, "status");
    }

    public ScreenElementMapping withStatus(VerificationStatus newStatus) {
        return new ScreenElementMapping(field, androidLocator, iosStrategy, iosLocator, newStatus);
    }

    public boolean isVerified() {
        return status == VerificationStatus.VERIFIED_STABLE;
    }
}
