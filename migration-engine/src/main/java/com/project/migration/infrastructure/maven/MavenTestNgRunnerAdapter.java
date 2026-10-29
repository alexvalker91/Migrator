package com.project.migration.infrastructure.maven;

import com.project.migration.domain.exception.TestRunnerException;
import com.project.migration.domain.model.Checks;
import com.project.migration.domain.model.Platform;
import com.project.migration.domain.model.code.TestMethodRef;
import com.project.migration.domain.model.config.EngineConfig;
import com.project.migration.domain.model.config.MavenConfig;
import com.project.migration.domain.model.run.CompilationError;
import com.project.migration.domain.model.run.CompilationResult;
import com.project.migration.domain.model.run.TestClassRunResult;
import com.project.migration.domain.model.run.TestFailure;
import com.project.migration.domain.model.run.TestOutcome;
import com.project.migration.domain.model.run.TestRunResult;
import com.project.migration.domain.port.TestRunnerPort;
import com.project.migration.infrastructure.process.CommandExecutor;
import com.project.migration.infrastructure.process.ProcessCommandExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * {@link TestRunnerPort} over Maven + TestNG. Every run starts by deleting the old {@code testng-results.xml},
 * so a result file can only come from this run. Failing tests and compiler errors are results; a run that
 * produced no result file (crash, timeout) is reported as {@link TestOutcome#ERROR} with the end of the Maven
 * output as the failure, so the pipeline treats it as an environment problem, not as a test failure.
 */
public final class MavenTestNgRunnerAdapter implements TestRunnerPort {

    private static final Logger LOG = LoggerFactory.getLogger(MavenTestNgRunnerAdapter.class);
    private static final int OUTPUT_TAIL_CHARS = 4_000;
    private static final int SEARCH_DEPTH = 5;
    static final String CLASS_RUN_NAME = "(class run)";

    private final Path projectRoot;
    private final MavenConfig config;
    private final CommandExecutor executor;
    private final MavenCommandBuilder commands;
    private final FailureArtifactFinder artifacts;
    private final Clock clock;

    public MavenTestNgRunnerAdapter(EngineConfig config) {
        this(config.projectRoot(), config.maven(), new ProcessCommandExecutor(), Clock.systemUTC());
    }

    public MavenTestNgRunnerAdapter(Path projectRoot, MavenConfig config, CommandExecutor executor, Clock clock) {
        this.projectRoot = Checks.notNull(projectRoot, "projectRoot").toAbsolutePath().normalize();
        this.config = Checks.notNull(config, "config");
        this.executor = Checks.notNull(executor, "executor");
        this.clock = Checks.notNull(clock, "clock");
        this.commands = new MavenCommandBuilder(config, this.projectRoot);
        this.artifacts = new FailureArtifactFinder(this.projectRoot);
    }

    @Override
    public CompilationResult compile() {
        CommandExecutor.CommandResult result = executor.run(commands.compile(), projectRoot, config.compileTimeout());
        if (result.succeeded()) {
            return CompilationResult.succeeded(result.duration());
        }
        List<CompilationError> errors = CompilerOutputParser.parse(result.output());
        if (errors.isEmpty()) {
            String why = result.timedOut() ? "Maven did not finish within " + config.compileTimeout().toMinutes() + " min"
                    : "Maven failed (exit " + result.exitCode() + ") without compiler errors: " + tail(result.output());
            errors = List.of(new CompilationError(projectRoot.resolve("pom.xml"), 0, why));
        }
        return CompilationResult.failed(errors, result.duration());
    }

    @Override
    public TestRunResult runTestMethod(TestMethodRef test, Platform platform) {
        deleteStaleResults();
        Instant started = clock.instant();
        CommandExecutor.CommandResult run = executor.run(
                commands.testMethod(test.classFqcn(), test.methodName(), platform), projectRoot, config.testTimeout());
        Optional<Path> resultsFile = findResultsFile();
        TestRunResult result = resultsFile.map(file -> methodResult(test, platform, run, file))
                .orElseGet(() -> errorResult(test, platform, run, config.testTimeout()));
        if (result.outcome() == TestOutcome.FAILED || result.outcome() == TestOutcome.SKIPPED) {
            result = result.withFailureArtifacts(artifacts.screenshot(test.methodName(), started),
                    artifacts.pageSource(test.methodName(), started));
        }
        return result;
    }

    @Override
    public TestClassRunResult runTestClass(String testClassFqcn, Platform platform) {
        deleteStaleResults();
        CommandExecutor.CommandResult run = executor.run(commands.testClass(testClassFqcn, platform), projectRoot,
                config.classTimeout());
        Optional<Path> resultsFile = findResultsFile();
        List<TestRunResult> results = new ArrayList<>();
        if (resultsFile.isPresent()) {
            try {
                for (TestNgResultsParser.MethodResult method : TestNgResultsParser.parse(resultsFile.get())) {
                    if (method.classFqcn().equals(testClassFqcn)) {
                        results.add(new TestRunResult(new TestMethodRef(testClassFqcn, method.methodName()), platform,
                                method.outcome(), failureFor(method), run.duration(), resultsFile));
                    }
                }
            } catch (IOException | SAXException e) {
                throw new TestRunnerException("Cannot read " + resultsFile.get() + ": " + e.getMessage(), e);
            }
        }
        if (results.isEmpty()) {
            results.add(errorResult(new TestMethodRef(testClassFqcn, CLASS_RUN_NAME), platform, run, config.classTimeout()));
        }
        return new TestClassRunResult(testClassFqcn, platform, results, run.duration());
    }

    private TestRunResult methodResult(TestMethodRef test, Platform platform, CommandExecutor.CommandResult run,
                                       Path resultsFile) {
        try {
            Optional<TestNgResultsParser.MethodResult> found = TestNgResultsParser.parse(resultsFile).stream()
                    .filter(m -> m.methodName().equals(test.methodName()) && m.classFqcn().equals(test.classFqcn()))
                    .findFirst();
            if (found.isPresent()) {
                TestNgResultsParser.MethodResult method = found.get();
                return new TestRunResult(test, platform, method.outcome(), failureFor(method), run.duration(),
                        Optional.of(resultsFile));
            }
            Optional<TestFailure> configFailure = TestNgResultsParser.configurationFailure(resultsFile, test.classFqcn());
            if (configFailure.isPresent()) {
                return new TestRunResult(test, platform, TestOutcome.SKIPPED, configFailure, run.duration(),
                        Optional.of(resultsFile));
            }
            return new TestRunResult(test, platform, TestOutcome.ERROR, Optional.of(new TestFailure(
                    "the results hold no run of " + test.key() + " (is the test name right and not filtered out by groups?)",
                    tail(run.output()))), run.duration(), Optional.of(resultsFile));
        } catch (IOException | SAXException e) {
            throw new TestRunnerException("Cannot read " + resultsFile + ": " + e.getMessage(), e);
        }
    }

    private static Optional<TestFailure> failureFor(TestNgResultsParser.MethodResult method) {
        if (method.outcome() == TestOutcome.PASSED) {
            return Optional.empty();
        }
        if (method.failure().isPresent() || method.outcome() == TestOutcome.SKIPPED) {
            return method.failure();
        }
        return Optional.of(new TestFailure(method.outcome().name(), ""));
    }

    private static TestRunResult errorResult(TestMethodRef test, Platform platform, CommandExecutor.CommandResult run,
                                             Duration timeout) {
        String message = run.timedOut() ? "Maven did not finish within " + timeout.toMinutes() + " min"
                : "Maven produced no testng-results.xml (exit " + run.exitCode() + ")";
        return new TestRunResult(test, platform, TestOutcome.ERROR,
                Optional.of(new TestFailure(message, tail(run.output()))), run.duration(), Optional.empty());
    }

    /** Old results must not be mistaken for the result of the run that is about to start. */
    private void deleteStaleResults() {
        for (Path file : resultFiles()) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                throw new TestRunnerException("Cannot delete the old result file " + file + ": " + e.getMessage(), e);
            }
        }
    }

    private Optional<Path> findResultsFile() {
        return resultFiles().stream().max(Comparator.comparing(MavenTestNgRunnerAdapter::modified));
    }

    private List<Path> resultFiles() {
        try (Stream<Path> files = Files.walk(projectRoot, SEARCH_DEPTH)) {
            return files.filter(file -> file.getFileName().toString().equals("testng-results.xml")
                    && file.getParent() != null && file.getParent().getFileName().toString().equals("surefire-reports"))
                    .toList();
        } catch (IOException e) {
            LOG.warn("Cannot search {} for test results: {}", projectRoot, e.getMessage());
            return List.of();
        }
    }

    private static java.time.Instant modified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toInstant();
        } catch (IOException e) {
            return java.time.Instant.EPOCH;
        }
    }

    private static String tail(String output) {
        String text = output.strip();
        return text.length() <= OUTPUT_TAIL_CHARS ? text : "..." + text.substring(text.length() - OUTPUT_TAIL_CHARS);
    }
}
