package com.project.migration.domain.port;

import com.project.migration.domain.exception.CacheCorruptedException;
import com.project.migration.domain.model.Platform;
import com.project.migration.domain.model.screen.ScreenId;
import com.project.migration.domain.model.screen.ScreenStateKey;
import com.project.migration.domain.model.screen.StateSnapshot;

import java.util.Optional;
import java.util.Set;

/**
 * Cleaned XML + screenshot store {@code .migration/snapshots/<ScreenName>/}, file names from
 * {@link ScreenStateKey#cleanXmlFileName(Platform)}. Snapshots are immutable under code &amp; app freeze;
 * saving an existing key overwrites it (used only when a snapshot is explicitly re-captured).
 */
public interface SnapshotRepositoryPort {

    boolean exists(ScreenStateKey key, Platform platform);

    /** @throws CacheCorruptedException if the files exist but cannot be read */
    Optional<StateSnapshot> find(ScreenStateKey key, Platform platform);

    /** Atomic write of the XML and, when present, the screenshot. */
    void save(StateSnapshot snapshot);

    /** Keys that have a snapshot for {@code platform}. */
    Set<ScreenStateKey> findKeys(ScreenId screen, Platform platform);
}
