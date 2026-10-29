package com.project.migration.application.orchestrator;

import com.project.migration.domain.model.Checks;
import com.project.migration.domain.model.Platform;
import com.project.migration.domain.model.code.TestMethodRef;
import com.project.migration.domain.model.run.TestClassRunResult;
import com.project.migration.domain.model.run.TestRunResult;
import com.project.migration.domain.port.MigrationLoggerPort;
import com.project.migration.domain.port.TestRunnerPort;

import java.util.ArrayList;
import java.util.List;

/**
 * Stage 5.2: the migration changed shared Page Objects, so the class is run once on Android. A method that does not
 * pass is run again alone; only a method that fails twice is a regression, a flake of the device is not.
 * Android tests are assumed green before the migration (code &amp; app freeze), so there is no baseline run.
 */
public final class AndroidRegressionGuard implements RegressionGuard {

    private final TestRunnerPort runner;
    private final MigrationLoggerPort logger;

    public AndroidRegressionGuard(TestRunnerPort runner, MigrationLoggerPort logger) {
        this.runner = Checks.notNull(runner, "runner");
        this.logger = Checks.notNull(logger, "logger");
    }

    @Override
    public RegressionResult check(String testClassFqcn) {
        TestClassRunResult run = runner.runTestClass(testClassFqcn, Platform.ANDROID);
        List<TestMethodRef> regressions = new ArrayList<>();
        for (TestRunResult result : run.results()) {
            if (result.isPassed()) {
                continue;
            }
            logger.warn("Android guard: " + result.test().methodName() + " did not pass (" + result.outcome()
                    + "), running it alone once more");
            TestRunResult second = runner.runTestMethod(result.test(), Platform.ANDROID);
            if (!second.isPassed()) {
                regressions.add(result.test());
            }
        }
        if (run.results().isEmpty()) {
            logger.warn("Android guard: the class run reported no tests");
        }
        return new RegressionResult(run, regressions);
    }
}
