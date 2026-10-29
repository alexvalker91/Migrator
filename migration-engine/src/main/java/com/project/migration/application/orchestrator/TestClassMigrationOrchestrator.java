package com.project.migration.application.orchestrator;

import com.project.migration.application.codegen.CodeAdapter;
import com.project.migration.application.delta.DeltaAnalyzer;
import com.project.migration.application.healing.CompilationRepairer;
import com.project.migration.application.snapshot.SnapshotCollector;
import com.project.migration.application.stability.GateFailureKind;
import com.project.migration.application.stability.GateOutcome;
import com.project.migration.application.stability.GateRequest;
import com.project.migration.application.stability.TestStabilizer;
import com.project.migration.application.telemetry.TelemetryCollector;
import com.project.migration.domain.model.Checks;
import com.project.migration.domain.model.MigrationStage;
import com.project.migration.domain.model.Platform;
import com.project.migration.domain.model.analysis.MigrationTaskDelta;
import com.project.migration.domain.model.analysis.PlatformScope;
import com.project.migration.domain.model.analysis.TestMethodTask;
import com.project.migration.domain.model.cache.TestCacheEntry;
import com.project.migration.domain.model.code.CodeNames;
import com.project.migration.domain.model.code.TestMethodRef;
import com.project.migration.domain.model.config.EngineConfig;
import com.project.migration.domain.model.orchestration.ClassMigrationOutcome;
import com.project.migration.domain.model.orchestration.MigrationExitStatus;
import com.project.migration.domain.model.orchestration.TestClassMigrationRequest;
import com.project.migration.domain.model.report.ClassMigrationReport;
import com.project.migration.domain.model.report.FailureEvidence;
import com.project.migration.domain.model.report.ReportArtifacts;
import com.project.migration.domain.model.report.TestMethodReport;
import com.project.migration.domain.model.report.TestMigrationStatus;
import com.project.migration.domain.model.run.CompilationError;
import com.project.migration.domain.model.run.CompilationResult;
import com.project.migration.domain.model.run.TestClassRunResult;
import com.project.migration.domain.model.telemetry.TelemetrySnapshot;
import com.project.migration.domain.model.telemetry.TestTelemetry;
import com.project.migration.domain.model.vcs.BranchNames;
import com.project.migration.domain.model.vcs.CommitId;
import com.project.migration.domain.port.CacheRepositoryPort;
import com.project.migration.domain.port.CodeAnalyzerPort;
import com.project.migration.domain.port.GitServicePort;
import com.project.migration.domain.port.MigrationLoggerPort;
import com.project.migration.domain.port.ReportGeneratorPort;
import com.project.migration.domain.port.TestRunnerPort;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The pipeline of one test class (ARCHITECTURE_SPEC section 3): delta analysis, snapshots, global components,
 * code generation with a compile gate, per-test stabilization, and the final iOS run, Android regression guard,
 * report, commit and push. The orchestrator only sequences; every stage is a service behind an interface.
 * Expected trouble (a test that cannot be saved, a red final run, uncompilable codegen) ends in a status and a
 * report, never in an exception; exceptions mean infrastructure failures.
 */
public final class TestClassMigrationOrchestrator {

    private static final int COMPILE_REPAIR_ROUNDS = 2;
    private static final Set<TestMigrationStatus> PASSING_ON_IOS = EnumSet.of(TestMigrationStatus.STABLE_PASS,
            TestMigrationStatus.HEALED_AND_PASSED, TestMigrationStatus.SKIPPED_ALREADY_VERIFIED);

    private final DeltaAnalyzer deltaAnalyzer;
    private final SnapshotCollector snapshotCollector;
    private final ComponentInitializer componentInitializer;
    private final CodeAdapter codeAdapter;
    private final CompilationRepairer compilationRepairer;
    private final TestStabilizer stabilizer;
    private final RegressionGuard regressionGuard;
    private final TestRunnerPort runner;
    private final GitServicePort git;
    private final ReportGeneratorPort reportGenerator;
    private final CodeAnalyzerPort analyzer;
    private final CacheRepositoryPort cache;
    private final TelemetryCollector telemetry;
    private final MigrationLoggerPort logger;
    private final ProjectProgressCalculator progress;
    private final EngineConfig config;
    private final Clock clock;

    public TestClassMigrationOrchestrator(DeltaAnalyzer deltaAnalyzer, SnapshotCollector snapshotCollector,
                                          ComponentInitializer componentInitializer, CodeAdapter codeAdapter,
                                          CompilationRepairer compilationRepairer, TestStabilizer stabilizer,
                                          RegressionGuard regressionGuard, TestRunnerPort runner, GitServicePort git,
                                          ReportGeneratorPort reportGenerator, CodeAnalyzerPort analyzer,
                                          CacheRepositoryPort cache, TelemetryCollector telemetry,
                                          MigrationLoggerPort logger, ProjectProgressCalculator progress,
                                          EngineConfig config, Clock clock) {
        this.deltaAnalyzer = Checks.notNull(deltaAnalyzer, "deltaAnalyzer");
        this.snapshotCollector = Checks.notNull(snapshotCollector, "snapshotCollector");
        this.componentInitializer = Checks.notNull(componentInitializer, "componentInitializer");
        this.codeAdapter = Checks.notNull(codeAdapter, "codeAdapter");
        this.compilationRepairer = Checks.notNull(compilationRepairer, "compilationRepairer");
        this.stabilizer = Checks.notNull(stabilizer, "stabilizer");
        this.regressionGuard = Checks.notNull(regressionGuard, "regressionGuard");
        this.runner = Checks.notNull(runner, "runner");
        this.git = Checks.notNull(git, "git");
        this.reportGenerator = Checks.notNull(reportGenerator, "reportGenerator");
        this.analyzer = Checks.notNull(analyzer, "analyzer");
        this.cache = Checks.notNull(cache, "cache");
        this.telemetry = Checks.notNull(telemetry, "telemetry");
        this.logger = Checks.notNull(logger, "logger");
        this.progress = Checks.notNull(progress, "progress");
        this.config = Checks.notNull(config, "config");
        this.clock = Checks.notNull(clock, "clock");
    }

    /**
     * @throws com.project.migration.domain.exception.MigrationException on LLM, MCP, Git, runner or code-modification
     *                                                                   infrastructure failures
     */
    public ClassMigrationOutcome migrate(TestClassMigrationRequest request) {
        String fqcn = request.testClassFqcn();
        Instant started = clock.instant();
        String branch = BranchNames.migrationBranch(fqcn);
        logger.startSession(fqcn);
        telemetry.startSession(fqcn);
        try {
            Set<Path> dirty = git.uncommittedChanges();
            if (!dirty.isEmpty()) {
                return refuse(fqcn, branch, started, dirty);
            }
            git.createOrCheckoutBranch(branch);
            CommitId branchStart = git.head();
            logger.info("Migrating " + fqcn + " on branch " + branch + " (from " + branchStart.abbreviated() + ")");

            stage(MigrationStage.STAGE_1_DELTA_ANALYSIS);
            MigrationTaskDelta delta = deltaAnalyzer.analyze(fqcn);
            List<TestMethodRef> allTests = analyzer.listTestMethods(fqcn);
            Map<TestMethodRef, TestMethodReport> reports = new LinkedHashMap<>();
            allTests.forEach(test -> reports.put(test, skippedReport(test, TestMigrationStatus.SKIPPED_ALREADY_VERIFIED)));

            if (!delta.tasks().isEmpty()) {
                if (!delta.missingSnapshots().isEmpty()) {
                    stage(MigrationStage.STAGE_2_SNAPSHOTS);
                    var collected = snapshotCollector.collect(delta);
                    collected.unreachable().forEach(state -> logger.warn("Snapshot not collected: " + state.key() + " on "
                            + state.platform() + " (" + state.reason() + ")"));
                }
                if (!config.globalComponents().isEmpty()) {
                    stage(MigrationStage.STAGE_0_GLOBALS);
                    var components = componentInitializer.initialize(delta);
                    components.unresolved().forEach(u -> logger.warn("Component field without iOS locator: " + u.field().key()
                            + " (" + u.reason() + ")"));
                }
                stage(MigrationStage.STAGE_3_CODEGEN);
                var adaptation = codeAdapter.adapt(delta);
                adaptation.unresolved().forEach(u -> logger.warn("Locator without iOS counterpart: " + u.field().key()
                        + " (" + u.reason() + ")"));
                adaptation.needsManual().forEach(m -> logger.warn("Method needs manual adaptation: " + m.method().key()
                        + " (" + m.reason() + ")"));
                Optional<CompilationResult> broken = compileWithRepair(branchStart);
                if (broken.isPresent()) {
                    return abortCodegen(request, branch, started, delta, allTests, reports, branchStart, broken.get());
                }
                CommitId baseline = git.commitAll(BranchNames.codegenCommitMessage(fqcn));
                logger.info("Codegen baseline " + baseline.abbreviated());

                boolean environmentDown = stabilize(delta, baseline, reports);
                if (environmentDown) {
                    return conclude(request, branch, started, allTests, reports, Optional.empty(), Optional.empty(),
                            MigrationExitStatus.ABORTED, "the environment looks down: "
                            + config.gate().maxConsecutiveEnvironmentFailures() + " tests in a row gave up on it; the rest is pending");
                }
            }
            return finishClass(request, branch, started, allTests, reports);
        } finally {
            logger.endSession();
        }
    }

    private boolean stabilize(MigrationTaskDelta delta, CommitId firstBaseline, Map<TestMethodRef, TestMethodReport> reports) {
        stage(MigrationStage.STAGE_4_STABILIZATION);
        CommitId baseline = firstBaseline;
        int environmentInARow = 0;
        boolean stopped = false;
        for (TestMethodTask task : delta.tasks()) {
            TestMethodRef test = task.test();
            if (stopped) {
                reports.put(test, skippedReport(test, TestMigrationStatus.PENDING));
                continue;
            }
            if (task.scope() == PlatformScope.ANDROID_ONLY) {
                reports.put(test, skippedReport(test, TestMigrationStatus.PLATFORM_SPECIFIC));
                cache.saveTest(new TestCacheEntry(test,
                        TestMigrationStatus.PLATFORM_SPECIFIC, clock.instant()));
                logger.info(test.methodName() + " is Android-only, not run on iOS");
                continue;
            }
            GateOutcome outcome = stabilizer.stabilize(new GateRequest(task, delta.screenOfClass(), baseline));
            baseline = outcome.head();
            TestTelemetry spent = telemetry.testSnapshot(test);
            reports.put(test, new TestMethodReport(test, outcome.status(), outcome.passStreak(), outcome.totalRuns(),
                    outcome.healingAttempts(), outcome.evidence(), spent.calls(), spent.stageTimings()));
            environmentInARow = outcome.failureKind() == GateFailureKind.ENVIRONMENT ? environmentInARow + 1 : 0;
            if (environmentInARow >= config.gate().maxConsecutiveEnvironmentFailures()) {
                logger.error("Stopping the class: " + environmentInARow + " tests in a row failed for environment reasons", null);
                stopped = true;
            }
        }
        return stopped;
    }

    /** Compiles; on errors asks for up to two repairs. Returns the failing result when it stays red. */
    private Optional<CompilationResult> compileWithRepair(CommitId branchStart) {
        CompilationResult compilation = runner.compile();
        for (int round = 0; !compilation.success() && round < COMPILE_REPAIR_ROUNDS; round++) {
            logger.warn("Generated code does not compile (" + compilation.errors().size() + " errors), repair round " + (round + 1));
            Optional<String> fixed = compilationRepairer.repair(compilation.errors(), git.changedFilesSince(branchStart));
            if (fixed.isEmpty()) {
                break;
            }
            logger.info("Compile fix applied: " + fixed.get());
            compilation = runner.compile();
        }
        return compilation.success() ? Optional.empty() : Optional.of(compilation);
    }

    private ClassMigrationOutcome abortCodegen(TestClassMigrationRequest request, String branch, Instant started,
                                               MigrationTaskDelta delta, List<TestMethodRef> allTests,
                                               Map<TestMethodRef, TestMethodReport> reports, CommitId branchStart,
                                               CompilationResult compilation) {
        Set<Path> touched = git.changedFilesSince(branchStart);
        if (!touched.isEmpty()) {
            git.restoreFiles(touched, branchStart);
        }
        String errors = compilation.errors().stream().limit(10).map(this::describe).collect(Collectors.joining("\n"));
        String verdict = "the generated code does not compile";
        for (TestMethodTask task : delta.tasks()) {
            if (task.scope() == PlatformScope.ANDROID_ONLY) {
                reports.put(task.test(), skippedReport(task.test(), TestMigrationStatus.PLATFORM_SPECIFIC));
                continue;
            }
            cache.saveTest(new TestCacheEntry(task.test(),
                    TestMigrationStatus.NEEDS_MANUAL_FIX, clock.instant()));
            reports.put(task.test(), new TestMethodReport(task.test(), TestMigrationStatus.NEEDS_MANUAL_FIX, 0, 0, List.of(),
                    Optional.of(new FailureEvidence(errors, Optional.empty(), Optional.empty(), verdict)), List.of(), Map.of()));
        }
        return conclude(request, branch, started, allTests, reports, Optional.empty(), Optional.empty(),
                MigrationExitStatus.ABORTED, verdict + "; " + touched.size() + " file(s) rolled back to "
                + branchStart.abbreviated());
    }

    private ClassMigrationOutcome finishClass(TestClassMigrationRequest request, String branch, Instant started,
                                           List<TestMethodRef> allTests, Map<TestMethodRef, TestMethodReport> reports) {
        stage(MigrationStage.STAGE_5_FINALIZE);
        String fqcn = request.testClassFqcn();
        Optional<TestClassRunResult> iosRun = Optional.empty();
        if (reports.values().stream().anyMatch(r -> PASSING_ON_IOS.contains(r.status()))) {
            iosRun = Optional.of(runner.runTestClass(fqcn, Platform.IOS));
            logger.info("Final iOS run: " + (iosRun.get().allPassed() ? "green" : "RED"));
        }
        Optional<RegressionResult> guard = Optional.empty();
        if (!allTests.isEmpty()) {
            guard = Optional.of(regressionGuard.check(fqcn));
            logger.info("Android regression guard: " + (guard.get().passed() ? "green" : "RED " + guard.get().regressions()));
        }
        boolean iosRed = iosRun.isPresent() && !iosRun.get().allPassed();
        boolean androidRed = guard.isPresent() && !guard.get().passed();
        long needFix = reports.values().stream().filter(r -> r.status() == TestMigrationStatus.NEEDS_MANUAL_FIX).count();
        MigrationExitStatus status;
        String message;
        if (iosRed || androidRed) {
            status = MigrationExitStatus.STAGE5_FAILED;
            message = (iosRed ? "the final iOS class run is red" : "") + (iosRed && androidRed ? "; " : "")
                    + (androidRed ? "Android regression in " + guard.get().regressions().stream()
                    .map(TestMethodRef::methodName).toList() : "");
        } else if (needFix > 0) {
            status = MigrationExitStatus.COMPLETED_WITH_MANUAL_FIX;
            message = needFix + " test(s) need a manual fix and are tagged ios-needs-fix";
        } else {
            status = MigrationExitStatus.SUCCESS;
            message = "all tests migrated";
        }
        return conclude(request, branch, started, allTests, reports, iosRun, guard.map(RegressionResult::run), status, message);
    }

    /** Builds the report, writes it, commits and pushes as far as the status and the request allow. */
    private ClassMigrationOutcome conclude(TestClassMigrationRequest request, String branch, Instant started,
                                           List<TestMethodRef> allTests, Map<TestMethodRef, TestMethodReport> reports,
                                           Optional<TestClassRunResult> iosRun, Optional<TestClassRunResult> androidRun,
                                           MigrationExitStatus status, String message) {
        telemetry.finish();
        TelemetrySnapshot snapshot = telemetry.snapshot();
        List<TestMethodReport> ordered = allTests.stream().map(reports::get).toList();
        ClassMigrationReport report = new ClassMigrationReport(request.testClassFqcn(), branch, started, clock.instant(),
                ordered, snapshot.stageDurations(), snapshot, iosRun, androidRun, progress.calculate());
        Optional<ReportArtifacts> artifacts = generate(report);

        boolean pushed = false;
        if (status != MigrationExitStatus.ABORTED) {
            git.commitAll("migration: " + CodeNames.simpleName(request.testClassFqcn())
                    + " (" + report.count(TestMigrationStatus.STABLE_PASS) + " stable, "
                    + report.count(TestMigrationStatus.HEALED_AND_PASSED) + " healed, "
                    + report.count(TestMigrationStatus.NEEDS_MANUAL_FIX) + " need manual fix)");
            boolean allowed = status != MigrationExitStatus.STAGE5_FAILED || request.pushOnRegression();
            if (request.push() && allowed) {
                git.push(branch);
                pushed = true;
            } else if (request.push()) {
                logger.warn("Branch " + branch + " not pushed: stage 5 is red (use push-on-regression to publish it anyway)");
            }
        }
        logger.info("Migration of " + request.testClassFqcn() + " ended " + status + ": " + message);
        return new ClassMigrationOutcome(status, report, artifacts, pushed, message);
    }

    private Optional<ReportArtifacts> generate(ClassMigrationReport report) {
        try {
            return Optional.of(reportGenerator.generate(report));
        } catch (RuntimeException e) {
            logger.error("The report could not be generated", e);
            return Optional.empty();
        }
    }

    private ClassMigrationOutcome refuse(String fqcn, String branch, Instant started, Set<Path> dirty) {
        String files = dirty.stream().map(Path::toString).sorted().limit(20).collect(Collectors.joining(", "));
        String message = "the working tree has uncommitted changes (" + dirty.size() + " file(s): " + files
                + "); commit, stash or discard them, then run again";
        logger.error(message, null);
        telemetry.finish();
        ClassMigrationReport report = new ClassMigrationReport(fqcn, branch, started, clock.instant(), List.of(), Map.of(),
                telemetry.snapshot(), Optional.empty(), Optional.empty(), progress.calculate());
        return new ClassMigrationOutcome(MigrationExitStatus.ABORTED, report, Optional.empty(), false, message);
    }

    private TestMethodReport skippedReport(TestMethodRef test, TestMigrationStatus status) {
        return new TestMethodReport(test, status, 0, 0, List.of(), Optional.empty(), List.of(), Map.of());
    }

    private String describe(CompilationError error) {
        return error.file().getFileName() + ":" + error.line() + " " + error.message();
    }

    private void stage(MigrationStage stage) {
        logger.enterStage(stage);
        telemetry.enterStage(stage);
    }
}
