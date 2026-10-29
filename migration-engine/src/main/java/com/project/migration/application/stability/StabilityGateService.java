package com.project.migration.application.stability;

import com.project.migration.application.healing.HealingRequest;
import com.project.migration.application.healing.HealingResult;
import com.project.migration.application.healing.Healer;
import com.project.migration.application.pagesource.XmlPageSourceCleaner;
import com.project.migration.application.telemetry.TelemetryCollector;
import com.project.migration.domain.exception.CodeModificationException;
import com.project.migration.domain.model.Checks;
import com.project.migration.domain.model.MigrationStage;
import com.project.migration.domain.model.Platform;
import com.project.migration.domain.model.analysis.TestMethodTask;
import com.project.migration.domain.model.code.AddTestGroups;
import com.project.migration.domain.model.code.CodeChange;
import com.project.migration.domain.model.code.TestMethodRef;
import com.project.migration.domain.model.config.EngineConfig;
import com.project.migration.domain.model.mcp.RawPageSource;
import com.project.migration.domain.model.report.FailureEvidence;
import com.project.migration.domain.model.report.HealingAttempt;
import com.project.migration.domain.model.report.HealingOutcome;
import com.project.migration.domain.model.report.TestMigrationStatus;
import com.project.migration.domain.model.run.TestOutcome;
import com.project.migration.domain.model.run.TestRunResult;
import com.project.migration.domain.model.vcs.BranchNames;
import com.project.migration.domain.model.vcs.CommitId;
import com.project.migration.domain.port.ArtifactStorePort;
import com.project.migration.domain.port.CacheRepositoryPort;
import com.project.migration.domain.port.CodeAnalyzerPort;
import com.project.migration.domain.port.CodeModifierPort;
import com.project.migration.domain.port.GitServicePort;
import com.project.migration.domain.port.MigrationLoggerPort;
import com.project.migration.domain.port.TestRunnerPort;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Stage 4 for one test: run it on iOS until it passes {@code stabilityRuns} times in a row. A failing run goes to
 * {@link Healer} and resets the streak; runs that end SKIPPED/ERROR are repeated without touching the
 * code. {@code 3/3 PASS} marks the cache and creates a checkpoint commit. When the test cannot be saved the files
 * changed since the baseline are restored, and for code-related failures the test is tagged {@code ios-needs-fix}
 * in its own commit so later rollbacks keep the tag. Time and token budgets keep one test from consuming the run.
 */
public final class StabilityGateService implements TestStabilizer {

    private final TestRunnerPort runner;
    private final Healer healing;
    private final GitServicePort git;
    private final CodeModifierPort modifier;
    private final ArtifactStorePort artifacts;
    private final XmlPageSourceCleaner cleaner;
    private final TelemetryCollector telemetry;
    private final MigrationLoggerPort logger;
    private final EngineConfig config;
    private final Clock clock;
    private final VerificationRecorder recorder;

    public StabilityGateService(TestRunnerPort runner, Healer healing, GitServicePort git,
                                CodeModifierPort modifier, CodeAnalyzerPort analyzer, CacheRepositoryPort cache,
                                ArtifactStorePort artifacts, XmlPageSourceCleaner cleaner, TelemetryCollector telemetry,
                                MigrationLoggerPort logger, EngineConfig config, Clock clock) {
        this.runner = Checks.notNull(runner, "runner");
        this.healing = Checks.notNull(healing, "healing");
        this.git = Checks.notNull(git, "git");
        this.modifier = Checks.notNull(modifier, "modifier");
        this.artifacts = Checks.notNull(artifacts, "artifacts");
        this.cleaner = Checks.notNull(cleaner, "cleaner");
        this.telemetry = Checks.notNull(telemetry, "telemetry");
        this.logger = Checks.notNull(logger, "logger");
        this.config = Checks.notNull(config, "config");
        this.clock = Checks.notNull(clock, "clock");
        this.recorder = new VerificationRecorder(cache, Checks.notNull(analyzer, "analyzer"), config, clock);
    }

    /**
     * @throws com.project.migration.domain.exception.MigrationException on LLM, MCP, Git or runner infrastructure failures
     */
    @Override
    public GateOutcome stabilize(GateRequest request) {
        TestMethodTask task = request.task();
        TestMethodRef test = task.test();
        telemetry.enterTest(test);
        logger.enterTest(test);
        recorder.testStatus(task, TestMigrationStatus.IN_PROGRESS);
        try {
            return runGate(request);
        } finally {
            logger.exitTest();
            telemetry.exitTest();
        }
    }

    private GateOutcome runGate(GateRequest request) {
        TestMethodTask task = request.task();
        TestMethodRef test = task.test();
        Instant started = clock.instant();
        CommitId baseline = request.baseline();
        List<HealingAttempt> attempts = new ArrayList<>();
        List<List<CodeChange>> batches = new ArrayList<>();
        int streak = 0;
        int totalRuns = 0;
        int environmentFailures = 0;
        Optional<TestRunResult> failureToHeal = Optional.empty();
        TestRunResult lastRun = null;

        while (true) {
            if (budgetExceeded(test, started)) {
                return giveUp(request, GateFailureKind.BUDGET, "test budget exhausted (" + config.gate().maxTestDuration().toMinutes()
                        + " min or " + config.gate().maxTestTokens() + " tokens)", streak, totalRuns, attempts, lastRun);
            }
            TestRunResult run;
            if (failureToHeal.isPresent()) {
                run = failureToHeal.get();
                failureToHeal = Optional.empty();
            } else {
                telemetry.enterStage(MigrationStage.STAGE_4_STABILIZATION);
                run = runner.runTestMethod(test, Platform.IOS);
                totalRuns++;
                logger.info("iOS run " + totalRuns + " of " + test.methodName() + ": " + run.outcome());
            }
            lastRun = run;

            if (run.outcome() == TestOutcome.PASSED) {
                environmentFailures = 0;
                streak++;
                if (streak >= config.stabilityRuns()) {
                    return success(request, streak, totalRuns, attempts);
                }
                continue;
            }
            if (!run.isHealable()) {
                if (++environmentFailures > config.gate().environmentRetries()) {
                    return giveUp(request, GateFailureKind.ENVIRONMENT, "the environment failed " + environmentFailures
                            + " runs in a row (" + run.outcome() + "): " + run.failure().map(f -> f.message()).orElse("no details"),
                            streak, totalRuns, attempts, run);
                }
                logger.warn("Run ended " + run.outcome() + ", repeating without changes (" + environmentFailures + "/"
                        + config.gate().environmentRetries() + ")");
                continue;
            }

            environmentFailures = 0;
            if (attempts.size() >= config.maxHealingAttempts()) {
                return giveUp(request, GateFailureKind.EXHAUSTED, "self-healing used all " + config.maxHealingAttempts()
                        + " attempts and the test still fails: " + run.failure().map(f -> f.message()).orElse(""),
                        streak, totalRuns, attempts, run);
            }
            telemetry.enterStage(MigrationStage.STAGE_4_SELF_HEALING);
            HealingResult result = healing.heal(new HealingRequest(test, run, attempts.size() + 1, streak, baseline,
                    batches, attempts));
            telemetry.enterStage(MigrationStage.STAGE_4_STABILIZATION);
            switch (result.status()) {
                case DATA_EXHAUSTED -> {
                    return giveUp(request, GateFailureKind.DATA_EXHAUSTED, "test data exhausted (user/limit/OTP): "
                            + result.diagnosis(), streak, totalRuns, attempts, run);
                }
                case SHARED_FRAMEWORK -> {
                    return giveUp(request, GateFailureKind.SHARED_FRAMEWORK, "the cause is in a shared framework class that "
                            + "must be fixed by hand: " + result.diagnosis(), streak, totalRuns, attempts, run);
                }
                case ENVIRONMENT -> {
                    if (++environmentFailures > config.gate().environmentRetries()) {
                        return giveUp(request, GateFailureKind.ENVIRONMENT, "environment problem: " + result.diagnosis(),
                                streak, totalRuns, attempts, run);
                    }
                }
                case APPLIED -> {
                    batches.add(result.appliedChanges());
                    attempts.add(attemptOf(attempts.size() + 1, result, HealingOutcome.STILL_FAILING));
                    streak = 0;
                }
                case COMPILATION_FAILED -> {
                    attempts.add(attemptOf(attempts.size() + 1, result, HealingOutcome.COMPILATION_FAILED));
                    streak = 0;
                    failureToHeal = Optional.of(run);
                }
                case NO_VALID_CHANGES -> {
                    attempts.add(attemptOf(attempts.size() + 1, result, HealingOutcome.STILL_FAILING));
                    streak = 0;
                    failureToHeal = Optional.of(run);
                }
            }
        }
    }

    private GateOutcome success(GateRequest request, int streak, int totalRuns, List<HealingAttempt> attempts) {
        TestMethodTask task = request.task();
        List<HealingAttempt> finalAttempts = new ArrayList<>(attempts);
        if (!finalAttempts.isEmpty()) {
            HealingAttempt last = finalAttempts.removeLast();
            finalAttempts.add(new HealingAttempt(last.attemptNumber(), last.diagnosis(), last.reasoning(),
                    last.changeSummary(), last.outcome() == HealingOutcome.STILL_FAILING ? HealingOutcome.RESOLVED : last.outcome()));
        }
        TestMigrationStatus status = finalAttempts.isEmpty() ? TestMigrationStatus.STABLE_PASS
                : TestMigrationStatus.HEALED_AND_PASSED;
        recorder.verified(task, request.screenOfClass());
        recorder.testStatus(task, status);
        CommitId checkpoint = git.commitAll(BranchNames.checkpointCommitMessage(task.test().methodName(), config.stabilityRuns()));
        logger.info("Checkpoint " + checkpoint.abbreviated() + ": " + task.test().methodName() + " passed " + streak + "/"
                + config.stabilityRuns() + (finalAttempts.isEmpty() ? "" : " after " + finalAttempts.size() + " healing attempt(s)"));
        return new GateOutcome(status, GateFailureKind.NONE, streak, totalRuns, finalAttempts, Optional.empty(), checkpoint);
    }

    /** Restores the baseline, tags the test when the code is to blame, keeps the evidence and records the result. */
    private GateOutcome giveUp(GateRequest request, GateFailureKind kind, String verdict, int streak, int totalRuns,
                               List<HealingAttempt> attempts, TestRunResult lastRun) {
        TestMethodTask task = request.task();
        Set<Path> touched = git.changedFilesSince(request.baseline());
        if (!touched.isEmpty()) {
            git.restoreFiles(touched, request.baseline());
            logger.warn("Rolled back " + touched.size() + " file(s) to " + request.baseline().abbreviated());
        }
        boolean codeRelated = kind == GateFailureKind.EXHAUSTED || kind == GateFailureKind.BUDGET
                || kind == GateFailureKind.SHARED_FRAMEWORK;
        CommitId head = request.baseline();
        if (codeRelated) {
            head = isolate(task.test(), request.baseline());
            recorder.failed(task, request.screenOfClass());
        }
        recorder.testStatus(task, TestMigrationStatus.NEEDS_MANUAL_FIX);
        FailureEvidence evidence = evidenceOf(task.test(), lastRun, verdict);
        logger.warn("Test " + task.test().methodName() + " needs a manual fix: " + verdict);
        return new GateOutcome(TestMigrationStatus.NEEDS_MANUAL_FIX, kind, streak, totalRuns, attempts,
                Optional.of(evidence), head);
    }

    private CommitId isolate(TestMethodRef test, CommitId fallback) {
        try {
            modifier.apply(List.of(new AddTestGroups(test, Set.of(AddTestGroups.IOS_NEEDS_FIX))));
        } catch (CodeModificationException e) {
            logger.warn("Could not tag " + test.methodName() + " with ios-needs-fix: " + e.getMessage());
            return fallback;
        }
        return git.commitAll(BranchNames.isolationCommitMessage(test.methodName()));
    }

    private FailureEvidence evidenceOf(TestMethodRef test, TestRunResult run, String verdict) {
        String stackTrace = run == null ? "" : run.failure().map(f -> f.stackTrace().isBlank() ? f.message() : f.stackTrace()).orElse("");
        Path directory = config.reportDirFor(test.simpleClassName()).resolve("failures").resolve(test.methodName());
        Optional<Path> screenshot = run == null ? Optional.empty()
                : run.failureScreenshot().flatMap(file -> artifacts.copyInto(file, directory));
        Optional<Path> cleanXml = run == null ? Optional.empty()
                : run.failurePageSource().flatMap(artifacts::readText).flatMap(this::cleanQuietly)
                .flatMap(xml -> artifacts.writeText(directory.resolve("screen_clean.xml"), xml));
        return new FailureEvidence(stackTrace, screenshot, cleanXml, verdict);
    }

    private Optional<String> cleanQuietly(String rawXml) {
        try {
            var cleaned = cleaner.clean(new RawPageSource(Platform.IOS, rawXml, clock.instant()));
            telemetry.recordCleaning(cleaned);
            return Optional.of(cleaned.cleanedXml());
        } catch (RuntimeException e) {
            logger.warn("The failure screen XML could not be cleaned for the report: " + e.getMessage());
            return Optional.empty();
        }
    }

    private boolean budgetExceeded(TestMethodRef test, Instant started) {
        Duration elapsed = Duration.between(started, clock.instant());
        return elapsed.compareTo(config.gate().maxTestDuration()) > 0
                || telemetry.testSnapshot(test).totalTokens() > config.gate().maxTestTokens();
    }

    private static HealingAttempt attemptOf(int number, HealingResult result, HealingOutcome outcome) {
        return new HealingAttempt(number, result.diagnosis(), result.reasoning(), result.changeSummary(), outcome);
    }
}
