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
import com.project.migration.domain.model.Platform;
import com.project.migration.domain.model.analysis.MigrationTaskDelta;
import com.project.migration.domain.model.analysis.PlatformScope;
import com.project.migration.domain.model.analysis.TestMethodTask;
import com.project.migration.domain.model.code.AddIosLocator;
import com.project.migration.domain.model.code.FieldRef;
import com.project.migration.domain.model.code.IosLocatorStrategy;
import com.project.migration.domain.model.code.MethodRef;
import com.project.migration.domain.model.code.ModificationResult;
import com.project.migration.domain.model.code.TestMethodRef;
import com.project.migration.domain.model.codegen.CodeAdaptationResult;
import com.project.migration.domain.model.config.EngineConfig;
import com.project.migration.domain.model.orchestration.ClassMigrationOutcome;
import com.project.migration.domain.model.orchestration.MigrationExitStatus;
import com.project.migration.domain.model.orchestration.TestClassMigrationRequest;
import com.project.migration.domain.model.report.ClassMigrationReport;
import com.project.migration.domain.model.report.FailureEvidence;
import com.project.migration.domain.model.report.HealingAttempt;
import com.project.migration.domain.model.report.HealingOutcome;
import com.project.migration.domain.model.report.ReportArtifacts;
import com.project.migration.domain.model.report.TestMigrationStatus;
import com.project.migration.domain.model.run.CompilationError;
import com.project.migration.domain.model.run.CompilationResult;
import com.project.migration.domain.model.run.TestClassRunResult;
import com.project.migration.domain.model.screen.ComponentId;
import com.project.migration.domain.model.screen.ScreenId;
import com.project.migration.domain.model.snapshot.SnapshotCollectionResult;
import com.project.migration.infrastructure.ast.JavaParserAstAdapter;
import com.project.migration.testsupport.BankFixture;
import com.project.migration.testsupport.InMemoryCache;
import com.project.migration.testsupport.MutableClock;
import com.project.migration.testsupport.RecordingLogger;
import com.project.migration.testsupport.ScriptedRunner;
import com.project.migration.testsupport.SnapshotGit;
import com.project.migration.testsupport.TelemetryFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class TestClassMigrationOrchestratorTest {

    static final String CARDS_TEST = "com.bank.tests.CardsTest";
    static final String HOME = "com.bank.pages.HomePage";
    static final TestMethodRef OPEN = new TestMethodRef(CARDS_TEST, "openCardDetails");
    static final TestMethodRef GOOGLE_PAY = new TestMethodRef(CARDS_TEST, "payWithGooglePay");
    static final TestMethodRef BACK = new TestMethodRef(CARDS_TEST, "backFromCards");
    static final TestMethodRef LOGIN = new TestMethodRef(CARDS_TEST, "loginWithOverload");
    static final Instant NOW = Instant.parse("2026-10-30T10:00:00Z");

    @TempDir
    Path project;

    private final List<String> events = new ArrayList<>();
    private final InMemoryCache cache = new InMemoryCache();
    private final ScriptedRunner runner = new ScriptedRunner();
    private final RecordingLogger logger = new RecordingLogger();
    private final MutableClock clock = new MutableClock(NOW);
    private final List<ClassMigrationReport> reports = new ArrayList<>();
    private JavaParserAstAdapter analyzer;
    private SnapshotGit git;
    private TelemetryCollector telemetry;
    private EngineConfig config;
    private MigrationTaskDelta delta;

    private DeltaAnalyzer deltaAnalyzer;
    private SnapshotCollector collector = d -> {
        events.add("collect");
        return SnapshotCollectionResult.nothingToDo();
    };
    private ComponentInitializer components = d -> {
        events.add("components");
        return ComponentInitResult.nothingToDo();
    };
    private CodeAdapter adapter;
    private CompilationRepairer repairer = (errors, files) -> Optional.empty();
    private TestStabilizer stabilizer;
    private RegressionGuard guard;
    private com.project.migration.domain.port.ReportGeneratorPort generator = report -> {
        reports.add(report);
        return new ReportArtifacts(Path.of("dashboard.html"), Path.of("report.md"));
    };
    private Function<GateRequest, GateOutcome> gateAnswer;

    @BeforeEach
    void setUp() {
        BankFixture.copyTo(project);
        analyzer = BankFixture.analyzer(project);
        git = new SnapshotGit(project);
        config = TelemetryFixture.config(project);
        telemetry = TelemetryFixture.collector(config, logger, CARDS_TEST);
        delta = new MigrationTaskDelta(CARDS_TEST,
                List.of(task(OPEN, PlatformScope.BOTH), task(GOOGLE_PAY, PlatformScope.ANDROID_ONLY), task(LOGIN, PlatformScope.BOTH)),
                Set.of(com.project.migration.domain.model.screen.ScreenStateKey.of(new ScreenId("HomePage"),
                        com.project.migration.domain.model.screen.ScreenState.DEFAULT)),
                Set.of(), Set.of(), Set.of(), Set.of(), Map.of(HOME, new ScreenId("HomePage")));
        deltaAnalyzer = fqcn -> {
            events.add("analyze");
            return delta;
        };
        adapter = d -> {
            events.add("adapt");
            analyzer.modifier().apply(List.of(new AddIosLocator(new FieldRef(HOME, "cardsTile"),
                    IosLocatorStrategy.ACCESSIBILITY_ID, "cards-tile")));
            return emptyAdaptation();
        };
        gateAnswer = request -> passed(request, TestMigrationStatus.STABLE_PASS);
        stabilizer = request -> {
            events.add("stabilize " + request.task().test().methodName());
            return gateAnswer.apply(request);
        };
        guard = fqcn -> {
            events.add("android-guard");
            return new RegressionResult(new TestClassRunResult(fqcn, Platform.ANDROID, List.of(
                    ScriptedRunner.passed(OPEN, Platform.ANDROID)), Duration.ofSeconds(5)), List.of());
        };
    }

    @Test
    void runsTheStagesInOrderAndEndsWithACommitAndAPush() {
        config = config.withGlobalComponents(Map.of(new ComponentId("BottomNavBar"), "com.bank.pages.BottomNav"));
        cache.saveTest(new com.project.migration.domain.model.cache.TestCacheEntry(BACK, TestMigrationStatus.STABLE_PASS, NOW));
        runner.classRuns.add(new TestClassRunResult(CARDS_TEST, Platform.IOS, List.of(ScriptedRunner.passed(OPEN, Platform.IOS)),
                Duration.ofMinutes(1)));

        ClassMigrationOutcome outcome = orchestrator().migrate(TestClassMigrationRequest.of(CARDS_TEST));

        assertThat(outcome.status()).isEqualTo(MigrationExitStatus.SUCCESS);
        assertThat(outcome.pushed()).isTrue();
        assertThat(events).containsExactly("analyze", "collect", "components", "adapt", "stabilize openCardDetails",
                "stabilize loginWithOverload", "android-guard");
        assertThat(runner.calls).containsExactly("compile", "class " + CARDS_TEST + " IOS");
        assertThat(git.branchesCreated).containsExactly("migrate/ios-CardsTest");
        assertThat(git.messages()).containsExactly("initial", "codegen: CardsTest");
        assertThat(git.pushed).containsExactly("migrate/ios-CardsTest");
        ClassMigrationReport report = outcome.report();
        assertThat(reports).containsExactly(report);
        assertThat(report.tests()).extracting(t -> t.test().methodName() + ":" + t.status()).containsExactly(
                "openCardDetails:STABLE_PASS", "payWithGooglePay:PLATFORM_SPECIFIC", "backFromCards:SKIPPED_ALREADY_VERIFIED",
                "loginWithOverload:STABLE_PASS");
        assertThat(report.branch()).isEqualTo("migrate/ios-CardsTest");
        assertThat(report.iosClassRun()).isPresent();
        assertThat(report.androidRegressionRun()).isPresent();
        assertThat(report.progress().totalTests()).isGreaterThan(0);
        assertThat(cache.tests.get(GOOGLE_PAY).status()).isEqualTo(TestMigrationStatus.PLATFORM_SPECIFIC);
        assertThat(outcome.artifacts()).isPresent();
    }

    @Test
    void aDirtyWorkingTreeStopsBeforeAnythingHappens() {
        git.extraUncommitted.add(Path.of("app/src/main/java/com/bank/pages/HomePage.java"));

        ClassMigrationOutcome outcome = orchestrator().migrate(TestClassMigrationRequest.of(CARDS_TEST));

        assertThat(outcome.status()).isEqualTo(MigrationExitStatus.ABORTED);
        assertThat(outcome.status().exitCode()).isEqualTo(3);
        assertThat(outcome.message()).contains("uncommitted changes").contains("HomePage.java").contains("commit, stash or discard");
        assertThat(events).isEmpty();
        assertThat(git.branchesCreated).isEmpty();
        assertThat(outcome.artifacts()).isEmpty();
        assertThat(reports).isEmpty();
    }

    @Test
    void testsThatNeedAManualFixStillEndInAPushedBranchWithExitCodeOne() {
        gateAnswer = request -> request.task().test().equals(LOGIN) ? needsFix(request) : passed(request, TestMigrationStatus.HEALED_AND_PASSED);

        ClassMigrationOutcome outcome = orchestrator().migrate(TestClassMigrationRequest.of(CARDS_TEST));

        assertThat(outcome.status()).isEqualTo(MigrationExitStatus.COMPLETED_WITH_MANUAL_FIX);
        assertThat(outcome.status().exitCode()).isEqualTo(1);
        assertThat(outcome.pushed()).isTrue();
        assertThat(outcome.report().count(TestMigrationStatus.NEEDS_MANUAL_FIX)).isEqualTo(1);
        assertThat(outcome.report().tests().stream().filter(t -> t.test().equals(OPEN)).findFirst().orElseThrow().healingAttempts())
                .hasSize(1);
        assertThat(outcome.message()).contains("1 test(s) need a manual fix");
    }

    @Test
    void aRedFinalIosRunOrAndroidRegressionIsStage5FailedAndIsNotPushedUnlessAskedTo() {
        runner.classRuns.add(new TestClassRunResult(CARDS_TEST, Platform.IOS, List.of(ScriptedRunner.failed(OPEN, Platform.IOS, "x")),
                Duration.ofMinutes(1)));

        ClassMigrationOutcome red = orchestrator().migrate(TestClassMigrationRequest.of(CARDS_TEST));

        assertThat(red.status()).isEqualTo(MigrationExitStatus.STAGE5_FAILED);
        assertThat(red.pushed()).isFalse();
        assertThat(git.pushed).isEmpty();
        assertThat(git.messages()).last().isEqualTo("codegen: CardsTest");
        assertThat(red.message()).contains("final iOS class run is red");

        guard = fqcn -> new RegressionResult(new TestClassRunResult(fqcn, Platform.ANDROID, List.of(
                ScriptedRunner.failed(OPEN, Platform.ANDROID, "broke")), Duration.ofSeconds(5)), List.of(OPEN));
        ClassMigrationOutcome android = orchestrator().migrate(new TestClassMigrationRequest(CARDS_TEST, true, true));

        assertThat(android.status()).isEqualTo(MigrationExitStatus.STAGE5_FAILED);
        assertThat(android.pushed()).isTrue();
        assertThat(android.message()).contains("Android regression in [openCardDetails]");
    }

    @Test
    void pushCanBeSwitchedOff() {
        ClassMigrationOutcome outcome = orchestrator().migrate(new TestClassMigrationRequest(CARDS_TEST, false, false));

        assertThat(outcome.status()).isEqualTo(MigrationExitStatus.SUCCESS);
        assertThat(outcome.pushed()).isFalse();
        assertThat(git.pushed).isEmpty();
    }

    @Test
    void codegenThatStaysUncompilableAfterTwoRepairsIsRolledBackAndEveryTestNeedsAManualFix() throws IOException {
        String homeBefore = Files.readString(project.resolve("app/src/main/java/com/bank/pages/HomePage.java"));
        CompilationResult red = CompilationResult.failed(List.of(new CompilationError(Path.of("/w/HomePage.java"), 9,
                "cannot find symbol")), Duration.ofSeconds(1));
        runner.compilations.add(red);
        runner.compilations.add(red);
        runner.compilations.add(red);
        List<Integer> repairRounds = new ArrayList<>();
        repairer = (errors, files) -> {
            repairRounds.add(errors.size());
            assertThat(files).contains(Path.of("app/src/main/java/com/bank/pages/HomePage.java"));
            return Optional.of("tried");
        };

        ClassMigrationOutcome outcome = orchestrator().migrate(TestClassMigrationRequest.of(CARDS_TEST));

        assertThat(outcome.status()).isEqualTo(MigrationExitStatus.ABORTED);
        assertThat(repairRounds).hasSize(2);
        assertThat(runner.calls).containsExactly("compile", "compile", "compile");
        assertThat(Files.readString(project.resolve("app/src/main/java/com/bank/pages/HomePage.java"))).isEqualTo(homeBefore);
        assertThat(outcome.message()).contains("does not compile").contains("rolled back");
        assertThat(outcome.report().tests()).extracting(t -> t.test().methodName() + ":" + t.status()).containsExactly(
                "openCardDetails:NEEDS_MANUAL_FIX", "payWithGooglePay:PLATFORM_SPECIFIC", "backFromCards:SKIPPED_ALREADY_VERIFIED",
                "loginWithOverload:NEEDS_MANUAL_FIX");
        assertThat(outcome.report().tests().getFirst().failureEvidence().orElseThrow().stackTrace()).contains("HomePage.java:9 cannot find symbol");
        assertThat(events).doesNotContain("stabilize openCardDetails");
        assertThat(git.messages()).containsExactly("initial");
        assertThat(outcome.pushed()).isFalse();
        assertThat(reports).hasSize(1);
    }

    @Test
    void aRepairedCompilationLetsTheMigrationContinue() {
        runner.compilations.add(CompilationResult.failed(List.of(new CompilationError(Path.of("/w/A.java"), 1, "x")), Duration.ofSeconds(1)));
        repairer = (errors, files) -> Optional.of("fixed it");

        ClassMigrationOutcome outcome = orchestrator().migrate(TestClassMigrationRequest.of(CARDS_TEST));

        assertThat(outcome.status()).isEqualTo(MigrationExitStatus.SUCCESS);
        assertThat(runner.calls.subList(0, 2)).containsExactly("compile", "compile");
        assertThat(logger.contains("Compile fix applied: fixed it")).isTrue();
    }

    @Test
    void anEnvironmentThatKeepsFailingStopsTheClassAndLeavesTheRestPending() {
        gateAnswer = request -> environmentFailure(request);
        delta = new MigrationTaskDelta(CARDS_TEST, List.of(task(OPEN, PlatformScope.BOTH), task(BACK, PlatformScope.BOTH),
                task(LOGIN, PlatformScope.BOTH), task(GOOGLE_PAY, PlatformScope.BOTH)),
                Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Map.of(HOME, new ScreenId("HomePage")));

        ClassMigrationOutcome outcome = orchestrator().migrate(TestClassMigrationRequest.of(CARDS_TEST));

        assertThat(outcome.status()).isEqualTo(MigrationExitStatus.ABORTED);
        assertThat(events.stream().filter(e -> e.startsWith("stabilize"))).hasSize(3);
        assertThat(outcome.report().tests()).extracting(t -> t.test().methodName() + ":" + t.status()).containsExactly(
                "openCardDetails:NEEDS_MANUAL_FIX", "payWithGooglePay:PENDING", "backFromCards:NEEDS_MANUAL_FIX",
                "loginWithOverload:NEEDS_MANUAL_FIX");
        assertThat(outcome.message()).contains("environment looks down");
        assertThat(outcome.pushed()).isFalse();
        assertThat(events).doesNotContain("android-guard");
    }

    @Test
    void everyFinishedClassIsAppendedToTheHistoryExceptARefusedStart() {
        List<com.project.migration.domain.model.history.ClassRunSummary> history = new ArrayList<>();
        var recording = new com.project.migration.domain.port.RunHistoryPort() {
            @Override
            public void append(com.project.migration.domain.model.history.ClassRunSummary summary) {
                history.add(summary);
            }

            @Override
            public List<com.project.migration.domain.model.history.ClassRunSummary> findAll() {
                return history;
            }
        };
        gateAnswer = request -> request.task().test().equals(LOGIN) ? needsFix(request) : passed(request, TestMigrationStatus.STABLE_PASS);

        orchestrator(recording).migrate(TestClassMigrationRequest.of(CARDS_TEST));

        assertThat(history).singleElement().satisfies(run -> {
            assertThat(run.testClassFqcn()).isEqualTo(CARDS_TEST);
            assertThat(run.status()).isEqualTo(MigrationExitStatus.COMPLETED_WITH_MANUAL_FIX);
            assertThat(run.processedTests()).isEqualTo(2);
            assertThat(run.stableTests()).isEqualTo(1);
            assertThat(run.manualFixTests()).isEqualTo(1);
        });

        git.extraUncommitted.add(Path.of("x.java"));
        orchestrator(recording).migrate(TestClassMigrationRequest.of(CARDS_TEST));
        assertThat(history).hasSize(1);
    }

    @Test
    void aFailingHistoryDoesNotFailTheMigration() {
        var broken = new com.project.migration.domain.port.RunHistoryPort() {
            @Override
            public void append(com.project.migration.domain.model.history.ClassRunSummary summary) {
                throw new IllegalStateException("disk full");
            }

            @Override
            public List<com.project.migration.domain.model.history.ClassRunSummary> findAll() {
                return List.of();
            }
        };

        ClassMigrationOutcome outcome = orchestrator(broken).migrate(TestClassMigrationRequest.of(CARDS_TEST));

        assertThat(outcome.status()).isEqualTo(MigrationExitStatus.SUCCESS);
        assertThat(logger.contains("could not be added to the history: disk full")).isTrue();
    }

    @Test
    void aClassWhoseTestsAreAllVerifiedOnlyRunsTheFinalChecks() {
        delta = MigrationTaskDelta.empty(CARDS_TEST);
        cache.saveTest(new com.project.migration.domain.model.cache.TestCacheEntry(OPEN, TestMigrationStatus.STABLE_PASS, NOW));

        ClassMigrationOutcome outcome = orchestrator().migrate(TestClassMigrationRequest.of(CARDS_TEST));

        assertThat(outcome.status()).isEqualTo(MigrationExitStatus.SUCCESS);
        assertThat(events).containsExactly("analyze", "android-guard");
        assertThat(runner.calls).containsExactly("class " + CARDS_TEST + " IOS");
        assertThat(outcome.report().tests()).allMatch(t -> t.status() == TestMigrationStatus.SKIPPED_ALREADY_VERIFIED);
    }

    @Test
    void aFailingReportGeneratorDoesNotFailTheMigration() {
        generator = report -> {
            throw new IllegalStateException("template broken");
        };

        ClassMigrationOutcome outcome = orchestrator().migrate(TestClassMigrationRequest.of(CARDS_TEST));

        assertThat(outcome.status()).isEqualTo(MigrationExitStatus.SUCCESS);
        assertThat(outcome.artifacts()).isEmpty();
        assertThat(logger.contains("The report could not be generated")).isTrue();
    }

    @Test
    void snapshotsAreOnlyCollectedWhenSomeAreMissing() {
        delta = new MigrationTaskDelta(CARDS_TEST, List.of(task(OPEN, PlatformScope.BOTH)), Set.of(), Set.of(), Set.of(),
                Set.of(), Set.of(), Map.of(HOME, new ScreenId("HomePage")));

        orchestrator().migrate(TestClassMigrationRequest.of(CARDS_TEST));

        assertThat(events).doesNotContain("collect", "components");
    }

    private TestClassMigrationOrchestrator orchestrator() {
        telemetry = TelemetryFixture.collector(config, logger, CARDS_TEST);
        return new TestClassMigrationOrchestrator(deltaAnalyzer, collector, components, adapter, repairer, stabilizer, guard,
                runner, git, generator, analyzer, cache, telemetry, logger, new ProjectProgressCalculator(analyzer, cache),
                config, clock);
    }

    private TestClassMigrationOrchestrator orchestrator(com.project.migration.domain.port.RunHistoryPort history) {
        telemetry = TelemetryFixture.collector(config, logger, CARDS_TEST);
        return new TestClassMigrationOrchestrator(deltaAnalyzer, collector, components, adapter, repairer, stabilizer, guard,
                runner, git, generator, analyzer, cache, telemetry, logger, new ProjectProgressCalculator(analyzer, cache),
                config, clock, history);
    }

    private GateOutcome passed(GateRequest request, TestMigrationStatus status) {
        List<HealingAttempt> attempts = status == TestMigrationStatus.HEALED_AND_PASSED
                ? List.of(new HealingAttempt(1, "d", Optional.empty(), "s", HealingOutcome.RESOLVED)) : List.of();
        return new GateOutcome(status, GateFailureKind.NONE, 3, 3 + attempts.size(), attempts, Optional.empty(), git.head());
    }

    private GateOutcome needsFix(GateRequest request) {
        return new GateOutcome(TestMigrationStatus.NEEDS_MANUAL_FIX, GateFailureKind.EXHAUSTED, 0, 4, List.of(),
                Optional.of(new FailureEvidence("trace", Optional.empty(), Optional.empty(), "gave up")), git.head());
    }

    private GateOutcome environmentFailure(GateRequest request) {
        return new GateOutcome(TestMigrationStatus.NEEDS_MANUAL_FIX, GateFailureKind.ENVIRONMENT, 0, 3, List.of(),
                Optional.of(new FailureEvidence("", Optional.empty(), Optional.empty(), "environment")), git.head());
    }

    private static CodeAdaptationResult emptyAdaptation() {
        return new CodeAdaptationResult(new ModificationResult(List.of(), 0), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of());
    }

    private static TestMethodTask task(TestMethodRef test, PlatformScope scope) {
        return new TestMethodTask(test, List.of(MethodRef.of(HOME, "openCards")), Set.of(), Set.of(), scope);
    }
}
