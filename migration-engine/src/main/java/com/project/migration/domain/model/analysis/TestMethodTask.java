package com.project.migration.domain.model.analysis;

import com.project.migration.domain.model.Checks;
import com.project.migration.domain.model.code.MethodRef;
import com.project.migration.domain.model.code.TestMethodRef;
import com.project.migration.domain.model.screen.ScreenStateKey;

import java.util.List;
import java.util.Set;

/**
 * Migration work for one {@code @Test} method, produced by Stage 1.
 *
 * @param callTree Page Object / step methods reachable from the test, in first-call order, without duplicates
 */
public record TestMethodTask(
        TestMethodRef test,
        List<MethodRef> callTree,
        Set<ScreenStateKey> requiredStates,
        Set<ComplexityMarker> markers,
        PlatformScope scope) {

    public TestMethodTask {
        Checks.notNull(test, "test");
        callTree = List.copyOf(Checks.notNull(callTree, "callTree"));
        if (Set.copyOf(callTree).size() != callTree.size()) {
            throw new IllegalArgumentException("callTree must not contain duplicates");
        }
        requiredStates = Set.copyOf(Checks.notNull(requiredStates, "requiredStates"));
        markers = Set.copyOf(Checks.notNull(markers, "markers"));
        Checks.notNull(scope, "scope");
    }

    /** Complex platform logic needs the Tier-1 model in Stage 3. */
    public boolean requiresTier1Adaptation() {
        return !markers.isEmpty();
    }
}
