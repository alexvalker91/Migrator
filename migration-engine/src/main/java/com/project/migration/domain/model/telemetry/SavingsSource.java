package com.project.migration.domain.model.telemetry;

/**
 * Mechanism that avoided sending tokens to an LLM.
 */
public enum SavingsSource {
    /** Raw vs cleaned page source in {@code XmlPageSourceCleaner}. */
    XML_CLEANING,
    /** Snapshot reused from {@code .migration/snapshots} instead of a new MCP session. */
    SNAPSHOT_CACHE,
    /** Locators / steps already {@code VERIFIED_STABLE} in the migration cache. */
    MIGRATION_CACHE
}
