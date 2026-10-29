package com.project.migration.testsupport;

import com.project.migration.domain.model.Platform;
import com.project.migration.domain.model.screen.ScreenId;
import com.project.migration.domain.model.screen.ScreenStateKey;
import com.project.migration.domain.model.screen.StateSnapshot;
import com.project.migration.domain.port.SnapshotRepositoryPort;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Snapshot store in a map.
 */
public final class InMemorySnapshots implements SnapshotRepositoryPort {

    public record Slot(ScreenStateKey key, Platform platform) {
    }

    public final Map<Slot, StateSnapshot> stored = new HashMap<>();
    public int saves;

    @Override
    public boolean exists(ScreenStateKey key, Platform platform) {
        return stored.containsKey(new Slot(key, platform));
    }

    @Override
    public Optional<StateSnapshot> find(ScreenStateKey key, Platform platform) {
        return Optional.ofNullable(stored.get(new Slot(key, platform)));
    }

    @Override
    public void save(StateSnapshot snapshot) {
        saves++;
        stored.put(new Slot(snapshot.key(), snapshot.platform()), snapshot);
    }

    @Override
    public Set<ScreenStateKey> findKeys(ScreenId screen, Platform platform) {
        Set<ScreenStateKey> keys = new HashSet<>();
        stored.keySet().stream().filter(slot -> slot.platform() == platform && slot.key().screen().equals(screen))
                .forEach(slot -> keys.add(slot.key()));
        return keys;
    }

    public void put(ScreenStateKey key, Platform platform, String cleanXml) {
        save(new StateSnapshot(key, platform, cleanXml, Optional.empty(), java.time.Instant.parse("2026-10-30T09:00:00Z")));
        saves--;
    }
}
