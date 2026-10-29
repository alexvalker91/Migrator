package com.project.migration.domain.model.cache;

import com.project.migration.domain.model.Checks;
import com.project.migration.domain.model.code.TestMethodRef;
import com.project.migration.domain.model.report.TestMigrationStatus;

import java.time.Instant;

/**
 * Last known migration status of a test method; feeds project-wide progress and skip-if-verified.
 */
public record TestCacheEntry(TestMethodRef test, TestMigrationStatus status, Instant updatedAt) {

    public TestCacheEntry {
        Checks.notNull(test, "test");
        Checks.notNull(status, "status");
        Checks.notNull(updatedAt, "updatedAt");
    }
}
