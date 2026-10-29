package com.project.migration.domain.model.agent;

import com.project.migration.domain.model.Checks;

import java.util.Optional;

/**
 * One action of the Android pass, described by what the user sees, not by coordinates.
 *
 * @param target element as identified on screen: accessibility id, visible text or resource id
 * @param value  typed text or scroll direction, if the action has one
 */
public record WalkthroughStep(WalkthroughAction action, String target, Optional<String> value) {

    public WalkthroughStep {
        Checks.notNull(action, "action");
        Checks.notNull(target, "target");
        value = value == null ? Optional.empty() : value.filter(v -> !v.isBlank());
    }
}
