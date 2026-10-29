package com.project.migration.domain.model.cache;

import com.project.migration.domain.model.Checks;
import com.project.migration.domain.model.screen.ComponentId;

import java.time.Instant;
import java.util.List;

/**
 * Cached mapping of a cross-screen component, created once in Stage 0.
 */
public record GlobalComponentEntry(
        ComponentId component,
        List<ScreenElementMapping> mappings,
        VerificationStatus status,
        Instant updatedAt) {

    public GlobalComponentEntry {
        Checks.notNull(component, "component");
        mappings = List.copyOf(Checks.notNull(mappings, "mappings"));
        Checks.notNull(status, "status");
        Checks.notNull(updatedAt, "updatedAt");
    }
}
