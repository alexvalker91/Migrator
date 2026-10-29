package com.project.migration.domain.model.snapshot;

import com.project.migration.domain.model.Checks;
import com.project.migration.domain.model.Platform;
import com.project.migration.domain.model.screen.ScreenStateKey;

/**
 * A required screen state that has no snapshot pair after Stage 2.
 *
 * @param platform platform on which the state was not reached
 */
public record UnreachableState(ScreenStateKey key, Platform platform, UnreachableReason reason, String detail) {

    public UnreachableState {
        Checks.notNull(key, "key");
        Checks.notNull(platform, "platform");
        Checks.notNull(reason, "reason");
        Checks.notNull(detail, "detail");
    }
}
