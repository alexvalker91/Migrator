package com.project.migration.application.stability;

import com.project.migration.application.healing.HealingRequest;
import com.project.migration.application.healing.HealingResult;
import com.project.migration.application.healing.HealingStatus;
import com.project.migration.application.pagesource.XmlPageSourceCleaner;
import com.project.migration.application.telemetry.TelemetryCollector;
import com.project.migration.domain.model.MigrationStage;
import com.project.migration.domain.model.Platform;
import com.project.migration.domain.model.analysis.PlatformScope;
import com.project.migration.domain.model.analysis.TestMethodTask;
import com.project.migration.domain.model.cache.GlobalComponentEntry;
import com.project.migration.domain.model.cache.ScreenCacheEntry;
import com.project.migration.domain.model.cache.ScreenElementMapping;
import com.project.migration.domain.model.cache.StepCacheEntry;
import com.project.migration.domain.model.cache.VerificationStatus;
import com.project.migration.domain.model.code.AddIosLocator;
import com.project.migration.domain.model.code.CodeChange;
import com.project.migration.domain.model.code.FieldRef;
import com.project.migration.domain.model.code.IosLocatorStrategy;
import com.project.migration.domain.model.code.MethodRef;
import com.project.migration.domain.model.code.TestMethodRef;
import com.project.migration.domain.model.config.EngineConfig;
import com.project.migration.domain.model.config.GateConfig;
import com.project.migration.domain.model.llm.LlmRequest;
import com.project.migration.domain.model.llm.LlmResponse;
import com.project.migration.domain.model.llm.LlmTier;
import com.project.migration.domain.model.llm.UserMessage;
import com.project.migration.domain.model.report.FailureEvidence;
import com.project.migration.domain.model.report.HealingOutcome;
import com.project.migration.domain.model.report.TestMigrationStatus;
import com.project.migration.domain.model.run.TestOutcome;
import com.project.migration.domain.model.screen.ComponentId;
import com.project.migration.domain.model.screen.ScreenId;
import com.project.migration.domain.model.screen.ScreenState;
import com.project.migration.domain.model.screen.ScreenStateKey;
import com.project.migration.domain.model.telemetry.TokenUsage;
import com.project.migration.infrastructure.ast.JavaParserAstAdapter;
import com.project.migration.testsupport.BankFixture;
import com.project.migration.testsupport.FakeArtifacts;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class StabilityGateServiceTest {

    static final String HOME = "com.bank.pages.HomePage";
    static final String CARDS_TEST = "com.bank.tests.CardsTest";
    static final TestMethodRef TEST = new TestMethodRef(CARDS_TEST, "openCardDetails");
    static final ScreenId HOME_SCREEN = new ScreenId("HomePage");
    static final MethodRef OPEN_CARDS = MethodRef.of(HOME, "openCards");
    static final Instant NOW = Instant.parse("2026-10-30T10:00:00Z");

    @TempDir
    Path project;

    private final InMemoryCache cache = new InMemoryCache();
    private final ScriptedRunner runner = new ScriptedRunner();
    private final FakeArtifacts artifacts = new FakeArtifacts();
    private final RecordingLogger logger = new RecordingLogger();
    private final MutableClock clock = new MutableClock(NOW);
    private final Deque<Function<HealingRequest, HealingResult>> healings = new ArrayDeque<>();
    private final List<HealingRequest> healingRequests = new ArrayList<>();
    private JavaParserAstAdapter analyzer;
    private SnapshotGit git;
    private TelemetryCollector telemetry;
    private EngineConfig config;
    private StabilityGateService gate;

    @BeforeEach
    void setUp() {
        BankFixture.copyTo(project);
        analyzer = BankFixture.analyzer(project);
        git = new SnapshotGit(project);
        config = TelemetryFixture.config(project);
        gate = gateWith(config);
    }

    private StabilityGateService gateWith(EngineConfig engineConfig) {
        config = engineConfig;
        telemetry = TelemetryFixture.collector(engineConfig, logger, CARDS_TEST);
        return new StabilityGateService(runner, request -> {
            healingRequests.add(request);
            return healings.poll().apply(request);
        }, git, analyzer.modifier(), analyzer, cache, artifacts, new XmlPageSourceCleaner(), telemetry, logger,
                engineConfig, clock);
    }

    @Test
    void threePassesInARowGiveAStablePassAndACheckpointAndMarkTheCacheVerified() {
        cache.saveScreen(ScreenCacheEntry.empty(HOME_SCREEN, NOW).withMapping(mapping("cardsTile"), NOW)
                .withState(ScreenStateKey.of(HOME_SCREEN, ScreenState.DEFAULT), VerificationStatus.UNVERIFIED, NOW));
        cache.saveGlobalComponent(new GlobalComponentEntry(new ComponentId("BottomNavBar"), List.of(mapping("paymentsTab")),
                VerificationStatus.UNVERIFIED, NOW));
        gate = gateWith(config.withGlobalComponents(Map.of(new ComponentId("BottomNavBar"), HOME)));

        GateOutcome outcome = gate.stabilize(request());

        assertThat(outcome.status()).isEqualTo(TestMigrationStatus.STABLE_PASS);
        assertThat(outcome.passed()).isTrue();
        assertThat(outcome.passStreak()).isEqualTo(3);
        assertThat(outcome.totalRuns()).isEqualTo(3);
        assertThat(outcome.healingAttempts()).isEmpty();
        assertThat(runner.calls).containsExactly("run openCardDetails IOS", "run openCardDetails IOS", "run openCardDetails IOS");
        assertThat(git.messages()).containsExactly("initial");
        assertThat(git.head()).isEqualTo(outcome.head());
        assertThat(cache.steps.get(OPEN_CARDS).status()).isEqualTo(VerificationStatus.VERIFIED_STABLE);
        assertThat(cache.steps.get(OPEN_CARDS).verifiedBy()).contains(TEST);
        ScreenCacheEntry screen = cache.findScreen(HOME_SCREEN).orElseThrow();
        assertThat(screen.mappings()).singleElement().satisfies(m -> assertThat(m.status()).isEqualTo(VerificationStatus.VERIFIED_STABLE));
        assertThat(screen.states()).containsEntry(ScreenStateKey.of(HOME_SCREEN, ScreenState.DEFAULT), VerificationStatus.VERIFIED_STABLE);
        assertThat(cache.tests.get(TEST).status()).isEqualTo(TestMigrationStatus.STABLE_PASS);
        assertThat(cache.components.values()).singleElement().satisfies(c -> {
            assertThat(c.status()).isEqualTo(VerificationStatus.VERIFIED_STABLE);
            assertThat(c.mappings()).allMatch(ScreenElementMapping::isVerified);
        });
    }

    @Test
    void aFailureIsHealedTheStreakStartsOverAndTheLastAttemptIsMarkedResolved() throws IOException {
        runner.methodRuns.add(ScriptedRunner.passed(TEST, Platform.IOS));
        runner.methodRuns.add(ScriptedRunner.failed(TEST, Platform.IOS, "second run failed"));
        healings.add(request -> applied(request, "tile has no iOS locator"));

        GateOutcome outcome = gate.stabilize(request());

        assertThat(outcome.status()).isEqualTo(TestMigrationStatus.HEALED_AND_PASSED);
        assertThat(outcome.totalRuns()).isEqualTo(5);
        assertThat(outcome.passStreak()).isEqualTo(3);
        assertThat(outcome.healingAttempts()).singleElement().satisfies(a -> {
            assertThat(a.attemptNumber()).isEqualTo(1);
            assertThat(a.diagnosis()).isEqualTo("tile has no iOS locator");
            assertThat(a.outcome()).isEqualTo(HealingOutcome.RESOLVED);
        });
        HealingRequest seen = healingRequests.getFirst();
        assertThat(seen.passesBeforeFailure()).isEqualTo(1);
        assertThat(seen.attemptNumber()).isEqualTo(1);
        assertThat(seen.baseline()).isEqualTo(git.commits.getFirst().id());
        assertThat(Files.readString(project.resolve("app/src/main/java/com/bank/pages/HomePage.java")))
                .contains("@iOSXCUITFindBy(accessibility = \"cards-tile-1\")");
        assertThat(git.commits.getLast().files().get(Path.of("app/src/main/java/com/bank/pages/HomePage.java")))
                .contains("@iOSXCUITFindBy(accessibility = \"cards-tile-1\")");
        assertThat(cache.tests.get(TEST).status()).isEqualTo(TestMigrationStatus.HEALED_AND_PASSED);
        assertThat(git.messages()).last().isEqualTo("checkpoint: openCardDetails passed 3/3 on iOS");
        assertThat(outcome.head()).isEqualTo(git.head());
    }

    @Test
    void whenAllAttemptsFailEverythingIsRolledBackTheTestIsTaggedAndIsolatedInItsOwnCommit() throws IOException {
        String homeBefore = Files.readString(project.resolve("app/src/main/java/com/bank/pages/HomePage.java"));
        runner.onMethod = (test, platform) -> ScriptedRunner.failed(test, platform, "always fails")
                .withFailureArtifacts(Optional.of(Path.of("target/screenshots/openCardDetails_9.png")),
                        Optional.of(Path.of("target/page-source/openCardDetails_9.xml")));
        artifacts.images.put(Path.of("target/screenshots/openCardDetails_9.png"), new byte[]{9});
        artifacts.texts.put(Path.of("target/page-source/openCardDetails_9.xml"),
                "<hierarchy><XCUIElementTypeButton name=\"ok\" visible=\"true\"/></hierarchy>");
        for (int i = 0; i < 3; i++) {
            int attempt = i + 1;
            healings.add(request -> applied(request, "attempt " + attempt + " diagnosis"));
        }
        cache.saveStep(StepCacheEntry.verified(MethodRef.of("com.bank.pages.CardsPage", "firstCardTitle"),
                new TestMethodRef(CARDS_TEST, "other"), NOW));

        GateOutcome outcome = gate.stabilize(request());

        assertThat(outcome.status()).isEqualTo(TestMigrationStatus.NEEDS_MANUAL_FIX);
        assertThat(outcome.failureKind()).isEqualTo(GateFailureKind.EXHAUSTED);
        assertThat(outcome.healingAttempts()).hasSize(3).allSatisfy(a -> assertThat(a.outcome()).isEqualTo(HealingOutcome.STILL_FAILING));
        assertThat(healingRequests).extracting(HealingRequest::attemptNumber).containsExactly(1, 2, 3);
        assertThat(healingRequests.get(2).appliedBatches()).hasSize(2);
        assertThat(healingRequests.get(2).previousAttempts()).hasSize(2);
        assertThat(Files.readString(project.resolve("app/src/main/java/com/bank/pages/HomePage.java"))).isEqualTo(homeBefore);
        assertThat(Files.readString(BankFixture.source(project, CARDS_TEST, true)))
                .contains("@Test(description = \"Card details are shown\", dataProvider = \"cards\", groups = {\"ios-needs-fix\"})");
        assertThat(git.messages()).last().isEqualTo("isolate: openCardDetails -> ios-needs-fix");
        assertThat(git.restores).isNotEmpty();
        assertThat(outcome.head()).isEqualTo(git.head());
        FailureEvidence evidence = outcome.evidence().orElseThrow();
        assertThat(evidence.verdict()).contains("used all 3 attempts").contains("always fails");
        assertThat(evidence.stackTrace()).contains("trace of always fails");
        assertThat(evidence.screenshot()).isPresent();
        assertThat(evidence.cleanXml()).hasValueSatisfying(p -> assertThat(p.toString()).endsWith("failures/openCardDetails/screen_clean.xml"));
        assertThat(artifacts.texts.get(evidence.cleanXml().orElseThrow())).contains("<Button").contains("name=\"ok\"");
        assertThat(cache.tests.get(TEST).status()).isEqualTo(TestMigrationStatus.NEEDS_MANUAL_FIX);
        assertThat(cache.steps.get(OPEN_CARDS).status()).isEqualTo(VerificationStatus.FAILED);
        assertThat(cache.steps.get(MethodRef.of("com.bank.pages.CardsPage", "firstCardTitle")).status())
                .isEqualTo(VerificationStatus.VERIFIED_STABLE);
    }

    @Test
    void environmentRunsAreRepeatedWithoutHealingAndKeepTheStreak() {
        runner.methodRuns.add(ScriptedRunner.passed(TEST, Platform.IOS));
        runner.methodRuns.add(ScriptedRunner.outcome(TEST, Platform.IOS, TestOutcome.SKIPPED));
        runner.methodRuns.add(ScriptedRunner.outcome(TEST, Platform.IOS, TestOutcome.ERROR));

        GateOutcome outcome = gate.stabilize(request());

        assertThat(outcome.status()).isEqualTo(TestMigrationStatus.STABLE_PASS);
        assertThat(outcome.totalRuns()).isEqualTo(5);
        assertThat(healingRequests).isEmpty();
    }

    @Test
    void anEnvironmentThatKeepsFailingGivesUpWithoutTaggingOrRollbackOfAnythingCommitted() {
        runner.onMethod = (test, platform) -> ScriptedRunner.outcome(test, platform, TestOutcome.ERROR);

        GateOutcome outcome = gate.stabilize(request());

        assertThat(outcome.failureKind()).isEqualTo(GateFailureKind.ENVIRONMENT);
        assertThat(outcome.status()).isEqualTo(TestMigrationStatus.NEEDS_MANUAL_FIX);
        assertThat(outcome.totalRuns()).isEqualTo(3);
        assertThat(outcome.evidence().orElseThrow().verdict()).contains("environment failed 3 runs in a row");
        assertThat(git.messages()).containsExactly("initial");
        assertThat(cache.steps).isEmpty();
        assertThat(cache.tests.get(TEST).status()).isEqualTo(TestMigrationStatus.NEEDS_MANUAL_FIX);
    }

    @Test
    void exhaustedTestDataAndSharedFrameworkCausesStopTheGateImmediately() throws IOException {
        runner.onMethod = (test, platform) -> ScriptedRunner.failed(test, platform, "boom");
        healings.add(request -> noCode(HealingStatus.DATA_EXHAUSTED, "no more transfers"));
        GateOutcome data = gate.stabilize(request());

        assertThat(data.failureKind()).isEqualTo(GateFailureKind.DATA_EXHAUSTED);
        assertThat(data.healingAttempts()).isEmpty();
        assertThat(data.evidence().orElseThrow().verdict()).contains("test data exhausted").contains("no more transfers");
        assertThat(Files.readString(BankFixture.source(project, CARDS_TEST, true))).doesNotContain("ios-needs-fix");
        assertThat(git.messages()).containsExactly("initial");

        healings.add(request -> noCode(HealingStatus.SHARED_FRAMEWORK, "BasePage scroll"));
        GateOutcome shared = gate.stabilize(request());

        assertThat(shared.failureKind()).isEqualTo(GateFailureKind.SHARED_FRAMEWORK);
        assertThat(shared.evidence().orElseThrow().verdict()).contains("shared framework class").contains("BasePage scroll");
        assertThat(Files.readString(BankFixture.source(project, CARDS_TEST, true))).contains("ios-needs-fix");
        assertThat(git.messages()).last().isEqualTo("isolate: openCardDetails -> ios-needs-fix");
    }

    @Test
    void aFailedFixAttemptDoesNotRerunTheTestButHealsTheSameFailureAgain() {
        runner.methodRuns.add(ScriptedRunner.failed(TEST, Platform.IOS, "first failure"));
        healings.add(request -> new HealingResult(HealingStatus.COMPILATION_FAILED, "d1", Optional.empty(), "did not compile", List.of()));
        healings.add(request -> applied(request, "d2"));

        GateOutcome outcome = gate.stabilize(request());

        assertThat(outcome.status()).isEqualTo(TestMigrationStatus.HEALED_AND_PASSED);
        assertThat(outcome.healingAttempts()).extracting(a -> a.outcome()).containsExactly(HealingOutcome.COMPILATION_FAILED, HealingOutcome.RESOLVED);
        assertThat(runner.calls.stream().filter(c -> c.startsWith("run")).count()).isEqualTo(1 + 3);
        assertThat(healingRequests.get(1).failedRun().failure().orElseThrow().message()).isEqualTo("first failure");
        assertThat(healingRequests.get(1).passesBeforeFailure()).isZero();
    }

    @Test
    void theWallClockBudgetEndsTheTest() {
        runner.onMethod = (test, platform) -> {
            clock.advance(Duration.ofMinutes(50));
            return ScriptedRunner.passed(test, platform);
        };

        GateOutcome outcome = gate.stabilize(request());

        assertThat(outcome.failureKind()).isEqualTo(GateFailureKind.BUDGET);
        assertThat(outcome.totalRuns()).isEqualTo(1);
        assertThat(outcome.evidence().orElseThrow().verdict()).contains("budget exhausted").contains("45 min");
        assertThat(git.messages()).last().isEqualTo("isolate: openCardDetails -> ios-needs-fix");
    }

    @Test
    void theTokenBudgetEndsTheTest() {
        gate = gateWith(config.withGate(new GateConfig(Duration.ofMinutes(45), 1_000, 2, 3)));
        runner.methodRuns.add(ScriptedRunner.failed(TEST, Platform.IOS, "x"));
        healings.add(request -> {
            telemetry.recordLlmCall(LlmRequest.text(LlmTier.TIER_1, MigrationStage.STAGE_4_SELF_HEALING, "heal",
                            List.of(UserMessage.of("hi"))),
                    new LlmResponse<>(Optional.of("ok"), Optional.empty(), List.of(), new TokenUsage(900, 300, 0, 0), "m",
                            Duration.ofMillis(1)));
            return applied(request, "expensive");
        });

        GateOutcome outcome = gate.stabilize(request());

        assertThat(outcome.failureKind()).isEqualTo(GateFailureKind.BUDGET);
        assertThat(outcome.healingAttempts()).hasSize(1);
        assertThat(outcome.evidence().orElseThrow().verdict()).contains("1000 tokens");
    }

    @Test
    void timeIsBookedToStabilizationAndHealingOfThatTest() {
        runner.onMethod = (test, platform) -> {
            clock.advance(Duration.ofSeconds(10));
            return ScriptedRunner.passed(test, platform);
        };

        gate.stabilize(request());

        assertThat(telemetry.testSnapshot(TEST).stageTimings()).containsKey(MigrationStage.STAGE_4_STABILIZATION);
        assertThat(telemetry.snapshot().llmCalls()).isEmpty();
    }

    private GateRequest request() {
        TestMethodTask task = new TestMethodTask(TEST, List.of(OPEN_CARDS),
                Set.of(ScreenStateKey.of(HOME_SCREEN, ScreenState.DEFAULT)), Set.of(), PlatformScope.BOTH);
        return new GateRequest(task, Map.of(HOME, HOME_SCREEN), git.head());
    }

    private HealingResult applied(HealingRequest request, String diagnosis) {
        CodeChange change = new AddIosLocator(new FieldRef(HOME, "cardsTile"), IosLocatorStrategy.ACCESSIBILITY_ID,
                "cards-tile-" + request.attemptNumber());
        analyzer.modifier().apply(List.of(change));
        return new HealingResult(HealingStatus.APPLIED, diagnosis, Optional.empty(), "added a locator", List.of(change));
    }

    private static HealingResult noCode(HealingStatus status, String diagnosis) {
        return new HealingResult(status, diagnosis, Optional.empty(), "none", List.of());
    }

    private static ScreenElementMapping mapping(String field) {
        return new ScreenElementMapping(new FieldRef(HOME, field), "accessibility = \"x\"", IosLocatorStrategy.ACCESSIBILITY_ID,
                "x", VerificationStatus.UNVERIFIED);
    }
}
