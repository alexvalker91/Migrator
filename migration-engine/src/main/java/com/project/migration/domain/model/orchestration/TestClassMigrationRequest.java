package com.project.migration.domain.model.orchestration;

import com.project.migration.domain.model.Checks;

/**
 * Input of one class migration.
 *
 * @param push             push the migration branch when the migration allows it
 * @param pushOnRegression push even when the Android regression guard is red
 */
public record TestClassMigrationRequest(String testClassFqcn, boolean push, boolean pushOnRegression) {

    public TestClassMigrationRequest {
        Checks.notBlank(testClassFqcn, "testClassFqcn");
    }

    public static TestClassMigrationRequest of(String testClassFqcn) {
        return new TestClassMigrationRequest(testClassFqcn, true, false);
    }
}
