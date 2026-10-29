package com.project.migration.domain.model.run;

import com.project.migration.domain.model.Checks;

/**
 * Failure details of a test run.
 */
public record TestFailure(String message, String stackTrace) {

    public TestFailure {
        Checks.notNull(message, "message");
        Checks.notNull(stackTrace, "stackTrace");
    }
}
