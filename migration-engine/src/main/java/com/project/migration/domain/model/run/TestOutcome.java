package com.project.migration.domain.model.run;

/**
 * Outcome of one test run as parsed from {@code testng-results.xml}.
 */
public enum TestOutcome {
    PASSED,
    /** Test body failed (assertion, locator, timeout inside the test) — eligible for self-healing. */
    FAILED,
    /** Skipped, usually because a configuration method ({@code @BeforeMethod}) failed — environment problem. */
    SKIPPED,
    /** The run could not be evaluated: Maven/JVM crash, no results file, runner timeout. */
    ERROR
}
