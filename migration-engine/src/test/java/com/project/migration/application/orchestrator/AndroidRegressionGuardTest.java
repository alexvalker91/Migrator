package com.project.migration.application.orchestrator;

import com.project.migration.domain.model.Platform;
import com.project.migration.domain.model.code.TestMethodRef;
import com.project.migration.domain.model.run.TestClassRunResult;
import com.project.migration.testsupport.RecordingLogger;
import com.project.migration.testsupport.ScriptedRunner;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AndroidRegressionGuardTest {

    static final String CLASS = "com.bank.tests.CardsTest";
    static final TestMethodRef A = new TestMethodRef(CLASS, "a");
    static final TestMethodRef B = new TestMethodRef(CLASS, "b");

    private final ScriptedRunner runner = new ScriptedRunner();
    private final RecordingLogger logger = new RecordingLogger();

    @Test
    void aGreenClassRunIsAllItTakes() {
        runner.classRuns.add(run(ScriptedRunner.passed(A, Platform.ANDROID), ScriptedRunner.passed(B, Platform.ANDROID)));

        RegressionResult result = new AndroidRegressionGuard(runner, logger).check(CLASS);

        assertThat(result.passed()).isTrue();
        assertThat(runner.calls).containsExactly("class " + CLASS + " ANDROID");
    }

    @Test
    void aMethodThatFailsOnlyOnceInTheClassRunIsAFlakeNotARegression() {
        runner.classRuns.add(run(ScriptedRunner.passed(A, Platform.ANDROID), ScriptedRunner.failed(B, Platform.ANDROID, "flaky")));
        runner.methodRuns.add(ScriptedRunner.passed(B, Platform.ANDROID));

        RegressionResult result = new AndroidRegressionGuard(runner, logger).check(CLASS);

        assertThat(result.passed()).isTrue();
        assertThat(result.run().results()).hasSize(2);
        assertThat(runner.calls).containsExactly("class " + CLASS + " ANDROID", "run b ANDROID");
        assertThat(logger.contains("running it alone once more")).isTrue();
    }

    @Test
    void aMethodThatFailsTwiceIsARegression() {
        runner.classRuns.add(run(ScriptedRunner.failed(A, Platform.ANDROID, "broken"), ScriptedRunner.passed(B, Platform.ANDROID)));
        runner.methodRuns.add(ScriptedRunner.failed(A, Platform.ANDROID, "still broken"));

        RegressionResult result = new AndroidRegressionGuard(runner, logger).check(CLASS);

        assertThat(result.passed()).isFalse();
        assertThat(result.regressions()).containsExactly(A);
    }

    private static TestClassRunResult run(com.project.migration.domain.model.run.TestRunResult... results) {
        return new TestClassRunResult(CLASS, Platform.ANDROID, List.of(results), Duration.ofSeconds(3));
    }
}
