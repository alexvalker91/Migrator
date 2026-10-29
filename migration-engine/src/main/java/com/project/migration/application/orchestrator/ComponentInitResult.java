package com.project.migration.application.orchestrator;

import com.project.migration.domain.model.Checks;
import com.project.migration.domain.model.codegen.UnresolvedLocator;
import com.project.migration.domain.model.screen.ComponentId;

import java.util.List;

/**
 * Result of Stage 0.
 *
 * @param initialized components that got a cache entry in this run
 * @param unresolved  component fields without an iOS locator
 */
public record ComponentInitResult(List<ComponentId> initialized, List<UnresolvedLocator> unresolved) {

    public ComponentInitResult {
        initialized = List.copyOf(Checks.notNull(initialized, "initialized"));
        unresolved = List.copyOf(Checks.notNull(unresolved, "unresolved"));
    }

    public static ComponentInitResult nothingToDo() {
        return new ComponentInitResult(List.of(), List.of());
    }
}
