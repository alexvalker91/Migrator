package com.project.migration.infrastructure.cache;

import com.project.migration.domain.model.cache.VerificationStatus;
import com.project.migration.domain.model.code.IosLocatorStrategy;
import com.project.migration.domain.model.report.TestMigrationStatus;
import com.project.migration.domain.model.screen.ScreenState;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * On-disk schema of {@code .migration/} (version {@link #SCHEMA_VERSION}), decoupled from the domain model so
 * domain refactorings do not invalidate a knowledge base accumulated over months. Keys are {@code snake_case}.
 * Nullable components are optional in the file.
 */
final class CacheJson {

    static final int SCHEMA_VERSION = 1;

    private CacheJson() {
    }

    /** {@code migration_cache.json}: global sections plus a derived, human-oriented summary. */
    record Index(int schemaVersion, List<GlobalComponent> globalComponents, List<LearnedRule> iosLearnedRules,
                 Summary summary) {

        static Index empty() {
            return new Index(SCHEMA_VERSION, List.of(), List.of(), null);
        }
    }

    /** Counts by status; regenerated after every save, never read by the engine. */
    record Summary(Instant generatedAt, Map<String, Long> screens, Map<String, Long> steps, Map<String, Long> tests) {
    }

    record GlobalComponent(String component, List<Mapping> mappings, VerificationStatus status, Instant updatedAt) {
    }

    record Mapping(String fieldClass, String fieldName, String androidLocator, IosLocatorStrategy iosStrategy,
                   String iosLocator, VerificationStatus status) {
    }

    record LearnedRule(String id, String description, TestRef learnedFrom, Instant learnedAt) {
    }

    record TestRef(String testClass, String method) {
    }

    /** {@code screens/<ScreenName>.json} */
    record ScreenShard(int schemaVersion, String screen, List<State> states, List<Mapping> mappings, Instant updatedAt) {
    }

    record State(ScreenState state, String variant, VerificationStatus status) {
    }

    /** {@code steps/<ClassFqcn>.json}: all verified methods of one Page Object / step class. */
    record StepShard(int schemaVersion, String stepClass, List<Step> steps) {
    }

    record Step(String method, List<String> parameterTypes, VerificationStatus status, TestRef verifiedBy,
                Instant updatedAt) {
    }

    /** {@code tests/<TestClassFqcn>.json} */
    record TestShard(int schemaVersion, String testClass, List<Test> tests) {
    }

    record Test(String method, TestMigrationStatus status, Instant updatedAt) {
    }
}
