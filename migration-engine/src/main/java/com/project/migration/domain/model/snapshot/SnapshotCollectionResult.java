package com.project.migration.domain.model.snapshot;

import com.project.migration.domain.model.Checks;
import com.project.migration.domain.model.screen.ScreenStateKey;

import java.util.List;
import java.util.Set;

/**
 * Result of Stage 2.
 *
 * @param captured    state pairs (Android + iOS) stored during this run
 * @param unreachable required states without a complete pair
 */
public record SnapshotCollectionResult(
        Set<ScreenStateKey> captured, List<UnreachableState> unreachable, List<AgentRunSummary> runs) {

    public SnapshotCollectionResult {
        captured = Set.copyOf(Checks.notNull(captured, "captured"));
        unreachable = List.copyOf(Checks.notNull(unreachable, "unreachable"));
        runs = List.copyOf(Checks.notNull(runs, "runs"));
    }

    public static SnapshotCollectionResult nothingToDo() {
        return new SnapshotCollectionResult(Set.of(), List.of(), List.of());
    }

    public boolean isComplete() {
        return unreachable.isEmpty();
    }
}
