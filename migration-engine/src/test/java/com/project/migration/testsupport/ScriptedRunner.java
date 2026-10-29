package com.project.migration.testsupport;

import com.project.migration.domain.model.Platform;
import com.project.migration.domain.model.code.TestMethodRef;
import com.project.migration.domain.model.run.CompilationResult;
import com.project.migration.domain.model.run.TestClassRunResult;
import com.project.migration.domain.model.run.TestFailure;
import com.project.migration.domain.model.run.TestOutcome;
import com.project.migration.domain.model.run.TestRunResult;
import com.project.migration.domain.port.TestRunnerPort;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * {@link TestRunnerPort} answering from queues; when a queue is empty, runs pass and compilation succeeds.
 */
public final class ScriptedRunner implements TestRunnerPort {

    public final Deque<CompilationResult> compilations = new ArrayDeque<>();
    public final Deque<TestRunResult> methodRuns = new ArrayDeque<>();
    public final Deque<TestClassRunResult> classRuns = new ArrayDeque<>();
    public BiFunction<TestMethodRef, Platform, TestRunResult> onMethod;
    public final List<String> calls = new ArrayList<>();
    public Runnable beforeEachRun = () -> { };

    @Override
    public CompilationResult compile() {
        calls.add("compile");
        beforeEachRun.run();
        return compilations.isEmpty() ? CompilationResult.succeeded(Duration.ofSeconds(1)) : compilations.poll();
    }

    @Override
    public TestRunResult runTestMethod(TestMethodRef test, Platform platform) {
        calls.add("run " + test.methodName() + " " + platform);
        beforeEachRun.run();
        if (!methodRuns.isEmpty()) {
            return methodRuns.poll();
        }
        return onMethod != null ? onMethod.apply(test, platform) : passed(test, platform);
    }

    @Override
    public TestClassRunResult runTestClass(String testClassFqcn, Platform platform) {
        calls.add("class " + testClassFqcn + " " + platform);
        beforeEachRun.run();
        return classRuns.isEmpty() ? new TestClassRunResult(testClassFqcn, platform,
                List.of(passed(new TestMethodRef(testClassFqcn, "anyTest"), platform)), Duration.ofSeconds(1))
                : classRuns.poll();
    }

    public static TestRunResult passed(TestMethodRef test, Platform platform) {
        return new TestRunResult(test, platform, TestOutcome.PASSED, Optional.empty(), Duration.ofSeconds(20), Optional.empty());
    }

    public static TestRunResult failed(TestMethodRef test, Platform platform, String message) {
        return new TestRunResult(test, platform, TestOutcome.FAILED, Optional.of(new TestFailure(message, "trace of " + message)),
                Duration.ofSeconds(20), Optional.empty());
    }

    public static TestRunResult outcome(TestMethodRef test, Platform platform, TestOutcome outcome) {
        return switch (outcome) {
            case PASSED -> passed(test, platform);
            case FAILED -> failed(test, platform, "failed");
            default -> new TestRunResult(test, platform, outcome, Optional.of(new TestFailure(outcome.name().toLowerCase(), "")),
                    Duration.ofSeconds(1), Optional.empty());
        };
    }
}
