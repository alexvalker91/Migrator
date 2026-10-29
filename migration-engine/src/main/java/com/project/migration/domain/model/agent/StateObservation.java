package com.project.migration.domain.model.agent;

import com.project.migration.domain.model.Checks;
import com.project.migration.domain.model.screen.ScreenStateKey;

import java.util.List;

/**
 * Texts the Android reference showed in one captured screen state; the iOS pass uses them to recognise the
 * same screen.
 */
public record StateObservation(ScreenStateKey key, List<String> visibleTexts) {

    public StateObservation {
        Checks.notNull(key, "key");
        visibleTexts = List.copyOf(Checks.notNull(visibleTexts, "visibleTexts"));
    }
}
