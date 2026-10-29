package com.project.migration.application.delta;

import com.project.migration.domain.model.screen.ScreenId;
import com.project.migration.domain.model.screen.ScreenStateKey;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validated part of a {@link DeltaAnalysis}.
 *
 * @param assignments page object FQCN → screen
 * @param states      test method name → required state keys
 * @param problems    what was dropped and why
 */
record ValidatedAnalysis(Map<String, ScreenId> assignments, Map<String, Set<ScreenStateKey>> states, List<String> problems) {
}
