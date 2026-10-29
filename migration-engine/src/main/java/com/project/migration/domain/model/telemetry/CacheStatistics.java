package com.project.migration.domain.model.telemetry;

import com.project.migration.domain.model.Checks;

/**
 * Hits and misses of the migration cache and snapshot store during a class migration.
 */
public record CacheStatistics(long hits, long misses) {

    public static final CacheStatistics EMPTY = new CacheStatistics(0, 0);

    public CacheStatistics {
        Checks.nonNegative(hits, "hits");
        Checks.nonNegative(misses, "misses");
    }

    public CacheStatistics plus(CacheStatistics other) {
        return new CacheStatistics(hits + other.hits, misses + other.misses);
    }

    /** Share of lookups served from cache, {@code 0.0} when there were none. */
    public double hitRate() {
        long total = hits + misses;
        return total == 0 ? 0.0 : (double) hits / total;
    }
}
