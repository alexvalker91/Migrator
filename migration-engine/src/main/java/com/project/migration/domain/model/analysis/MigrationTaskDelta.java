package com.project.migration.domain.model.analysis;

import com.project.migration.domain.model.Checks;
import com.project.migration.domain.model.code.FieldRef;
import com.project.migration.domain.model.code.MethodRef;
import com.project.migration.domain.model.screen.ScreenId;
import com.project.migration.domain.model.screen.ScreenStateKey;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Result of Stage 1: what still has to be done for a test class after subtracting everything already
 * {@code VERIFIED_STABLE} in the cache.
 */
public record MigrationTaskDelta(
        String testClassFqcn,
        List<TestMethodTask> tasks,
        Set<ScreenStateKey> missingSnapshots,
        Set<FieldRef> locatorsToMap,
        Set<MethodRef> stepsToAdapt,
        Set<MethodRef> alreadyVerifiedSteps,
        Set<ScreenId> alreadyVerifiedScreens) {

    public MigrationTaskDelta {
        Checks.notBlank(testClassFqcn, "testClassFqcn");
        tasks = List.copyOf(Checks.notNull(tasks, "tasks"));
        missingSnapshots = Set.copyOf(Checks.notNull(missingSnapshots, "missingSnapshots"));
        locatorsToMap = Set.copyOf(Checks.notNull(locatorsToMap, "locatorsToMap"));
        stepsToAdapt = Set.copyOf(Checks.notNull(stepsToAdapt, "stepsToAdapt"));
        alreadyVerifiedSteps = Set.copyOf(Checks.notNull(alreadyVerifiedSteps, "alreadyVerifiedSteps"));
        alreadyVerifiedScreens = Set.copyOf(Checks.notNull(alreadyVerifiedScreens, "alreadyVerifiedScreens"));
        Set<MethodRef> overlap = new HashSet<>(stepsToAdapt);
        overlap.retainAll(alreadyVerifiedSteps);
        if (!overlap.isEmpty()) {
            throw new IllegalArgumentException("steps both to adapt and already verified: " + overlap);
        }
    }

    /** Nothing left to snapshot, map or adapt — Stages 2–3 can be skipped. */
    public boolean isFullyCached() {
        return missingSnapshots.isEmpty() && locatorsToMap.isEmpty() && stepsToAdapt.isEmpty();
    }
}
