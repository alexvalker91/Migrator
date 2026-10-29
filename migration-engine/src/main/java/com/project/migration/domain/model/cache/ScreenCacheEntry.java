package com.project.migration.domain.model.cache;

import com.project.migration.domain.model.Checks;
import com.project.migration.domain.model.screen.ScreenId;
import com.project.migration.domain.model.screen.ScreenStateKey;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cached knowledge about one screen (one shard file in {@code .migration/screens/}).
 */
public record ScreenCacheEntry(
        ScreenId screen,
        Map<ScreenStateKey, VerificationStatus> states,
        List<ScreenElementMapping> mappings,
        Instant updatedAt) {

    public ScreenCacheEntry {
        Checks.notNull(screen, "screen");
        states = Map.copyOf(Checks.notNull(states, "states"));
        states.keySet().forEach(key -> {
            if (!key.screen().equals(screen)) {
                throw new IllegalArgumentException("state " + key + " does not belong to screen " + screen);
            }
        });
        mappings = List.copyOf(Checks.notNull(mappings, "mappings"));
        if (mappings.stream().map(ScreenElementMapping::field).distinct().count() != mappings.size()) {
            throw new IllegalArgumentException("duplicate field mapping for screen " + screen);
        }
        Checks.notNull(updatedAt, "updatedAt");
    }

    public static ScreenCacheEntry empty(ScreenId screen, Instant now) {
        return new ScreenCacheEntry(screen, Map.of(), List.of(), now);
    }

    /** A screen is stable once it has states and every state and mapping is {@code VERIFIED_STABLE}. */
    public VerificationStatus status() {
        boolean anyFailed = states.containsValue(VerificationStatus.FAILED)
                || mappings.stream().anyMatch(m -> m.status() == VerificationStatus.FAILED);
        if (anyFailed) {
            return VerificationStatus.FAILED;
        }
        boolean allVerified = !states.isEmpty()
                && states.values().stream().allMatch(s -> s == VerificationStatus.VERIFIED_STABLE)
                && mappings.stream().allMatch(ScreenElementMapping::isVerified);
        return allVerified ? VerificationStatus.VERIFIED_STABLE : VerificationStatus.UNVERIFIED;
    }

    public ScreenCacheEntry withState(ScreenStateKey key, VerificationStatus status, Instant now) {
        Map<ScreenStateKey, VerificationStatus> updated = new LinkedHashMap<>(states);
        updated.put(key, status);
        return new ScreenCacheEntry(screen, updated, mappings, now);
    }

    /** Adds or replaces the mapping of {@code mapping.field()}. */
    public ScreenCacheEntry withMapping(ScreenElementMapping mapping, Instant now) {
        List<ScreenElementMapping> updated = new ArrayList<>(
                mappings.stream().filter(m -> !m.field().equals(mapping.field())).toList());
        updated.add(mapping);
        return new ScreenCacheEntry(screen, states, updated, now);
    }
}
