package com.project.migration.application;

import com.project.migration.application.codegen.CodeAdaptationService;
import com.project.migration.application.codegen.LocatorMappingAnswer;
import com.project.migration.application.codegen.MethodRewrite;
import com.project.migration.application.delta.DeltaAnalysis;
import com.project.migration.application.delta.DeltaAnalyzerService;
import com.project.migration.application.healing.ChangeKind;
import com.project.migration.application.healing.CompilationRepairService;
import com.project.migration.application.healing.HealingProposal;
import com.project.migration.application.healing.HealingRootCause;
import com.project.migration.application.healing.SelfHealingService;
import com.project.migration.application.orchestrator.AndroidRegressionGuard;
import com.project.migration.application.orchestrator.GlobalComponentInitializer;
import com.project.migration.application.orchestrator.ProjectProgressCalculator;
import com.project.migration.application.orchestrator.TestClassMigrationOrchestrator;
import com.project.migration.application.pagesource.XmlPageSourceCleaner;
import com.project.migration.application.snapshot.SnapshotCollectionService;
import com.project.migration.application.stability.StabilityGateService;
import com.project.migration.application.telemetry.TelemetryCollector;
import com.project.migration.application.telemetry.TelemetryRecordingLlmClient;
import com.project.migration.domain.model.Platform;
import com.project.migration.domain.model.code.TestMethodRef;
import com.project.migration.domain.model.config.EngineConfig;
import com.project.migration.domain.model.llm.LlmRequest;
import com.project.migration.domain.model.orchestration.ClassMigrationOutcome;
import com.project.migration.domain.model.orchestration.MigrationExitStatus;
import com.project.migration.domain.model.orchestration.TestClassMigrationRequest;
import com.project.migration.domain.model.report.ReportArtifacts;
import com.project.migration.domain.model.report.TestMigrationStatus;
import com.project.migration.domain.model.run.TestClassRunResult;
import com.project.migration.domain.model.screen.ScreenId;
import com.project.migration.domain.model.screen.ScreenState;
import com.project.migration.domain.model.screen.ScreenStateKey;
import com.project.migration.infrastructure.ast.JavaParserAstAdapter;
import com.project.migration.testsupport.BankFixture;
import com.project.migration.testsupport.FakeArtifacts;
import com.project.migration.testsupport.FakeMcps;
import com.project.migration.testsupport.InMemoryCache;
import com.project.migration.testsupport.InMemorySnapshots;
import com.project.migration.testsupport.RecordingLogger;
import com.project.migration.testsupport.ScriptedLlm;
import com.project.migration.testsupport.ScriptedRunner;
import com.project.migration.testsupport.SnapshotGit;
import com.project.migration.testsupport.TelemetryFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole class pipeline (Stages 1, 3, 4, 5) with real services and faked ports: model, Maven runner, git, MCP.
 */
class Phase5PipelineTest {

    static final String CARDS_TEST = "com.bank.tests.CardsTest";
    static final String LOGIN = "com.bank.pages.LoginPage";
    static final TestMethodRef OPEN = new TestMethodRef(CARDS_TEST, "openCardDetails");
    static final TestMethodRef BACK = new TestMethodRef(CARDS_TEST, "backFromCards");
    static final TestMethodRef LOGIN_TEST = new TestMethodRef(CARDS_TEST, "loginWithOverload");

    @TempDir
    Path project;

    private final InMemoryCache cache = new InMemoryCache();
    private final InMemorySnapshots snapshots = new InMemorySnapshots();
    private final ScriptedLlm llm = new ScriptedLlm();
    private final ScriptedRunner runner = new ScriptedRunner();
    private final FakeMcps mcps = new FakeMcps("appium_get_page_source", "appium_gesture");
    private final RecordingLogger logger = new RecordingLogger();
    private final Map<String, Integer> runsPerTest = new HashMap<>();

    @Test
    void migratesTheCardsTestClassWithAHealedTestAndAnIsolatedOne() throws IOException {
        BankFixture.copyTo(project);
        EngineConfig config = TelemetryFixture.config(project).withFramework(BankFixture.FRAMEWORK);
        TelemetryCollector telemetry = TelemetryFixture.collector(config, logger, CARDS_TEST);
        JavaParserAstAdapter analyzer = BankFixture.analyzer(project);
        SnapshotGit git = new SnapshotGit(project);
        Clock clock = Clock.fixed(Instant.parse("2026-10-30T10:00:00Z"), ZoneOffset.UTC);
        FakeArtifacts artifacts = new FakeArtifacts();
        storeSnapshots();
        llm.otherwise(this::model);
        runner.onMethod = this::runTest;
        runner.classRuns.add(new TestClassRunResult(CARDS_TEST, Platform.IOS, List.of(ScriptedRunner.passed(OPEN, Platform.IOS),
                ScriptedRunner.passed(BACK, Platform.IOS)), Duration.ofMinutes(2)));
        List<com.project.migration.domain.model.report.ClassMigrationReport> generated = new java.util.ArrayList<>();

        var model = new TelemetryRecordingLlmClient(llm, telemetry, logger);
        var delta = new DeltaAnalyzerService(analyzer, cache, snapshots, model, telemetry, logger);
        var collector = new SnapshotCollectionService(mcps, snapshots, model, analyzer, (cls, w) -> { },
                new XmlPageSourceCleaner(), telemetry, logger, config.agentLoop(), clock);
        var adapter = new CodeAdaptationService(analyzer, analyzer.modifier(), cache, snapshots, model, logger, clock);
        var healing = new SelfHealingService(analyzer, analyzer.modifier(), runner, git, cache, snapshots, mcps, model, artifacts,
                new XmlPageSourceCleaner(), telemetry, logger, config, clock);
        var gate = new StabilityGateService(runner, healing, git, analyzer.modifier(), analyzer, cache, artifacts,
                new XmlPageSourceCleaner(), telemetry, logger, config, clock);
        var orchestrator = new TestClassMigrationOrchestrator(delta, collector,
                new GlobalComponentInitializer(analyzer, analyzer.modifier(), cache, snapshots, model, logger, config, clock),
                adapter, new CompilationRepairService(analyzer, analyzer.modifier(), model, artifacts, logger, config), gate,
                new AndroidRegressionGuard(runner, logger), runner, git, report -> {
                    generated.add(report);
                    return new ReportArtifacts(Path.of("dashboard.html"), Path.of("report.md"));
                }, analyzer, cache, telemetry, logger, new ProjectProgressCalculator(analyzer, cache), config, clock);

        ClassMigrationOutcome outcome = orchestrator.migrate(TestClassMigrationRequest.of(CARDS_TEST));

        assertThat(outcome.status()).isEqualTo(MigrationExitStatus.COMPLETED_WITH_MANUAL_FIX);
        assertThat(outcome.pushed()).isTrue();
        assertThat(outcome.report().tests()).extracting(t -> t.test().methodName() + ":" + t.status() + ":" + t.healingAttempts().size())
                .containsExactly("openCardDetails:STABLE_PASS:0", "payWithGooglePay:PLATFORM_SPECIFIC:0",
                        "backFromCards:HEALED_AND_PASSED:1", "loginWithOverload:NEEDS_MANUAL_FIX:3");
        assertThat(git.messages()).containsExactly("initial", "codegen: CardsTest",
                "checkpoint: backFromCards passed 3/3 on iOS", "isolate: loginWithOverload -> ios-needs-fix");

        String cards = Files.readString(BankFixture.source(project, "com.bank.pages.CardsPage", false));
        assertThat(cards).contains("iOSNsPredicate = \"type == 'XCUIElementTypeStaticText'\"").doesNotContain("UiScrollable");
        assertThat(Files.readString(BankFixture.source(project, "com.bank.pages.HomePage", false)))
                .contains("iOSNsPredicate = \"name == 'cards-tile' AND type == 'XCUIElementTypeButton'\"");
        String login = Files.readString(BankFixture.source(project, LOGIN, false));
        assertThat(login).contains("@iOSXCUITFindBy(accessibility = \"Email\")").doesNotContain("CONTAINS");
        String tests = Files.readString(BankFixture.source(project, CARDS_TEST, true));
        assertThat(tests).contains("groups = {\"ios-needs-fix\"}").contains("groups = {\"smoke\", \"android-only\"}");

        var loginReport = outcome.report().tests().stream().filter(t -> t.test().equals(LOGIN_TEST)).findFirst().orElseThrow();
        assertThat(loginReport.failureEvidence().orElseThrow().verdict()).contains("used all 3 attempts");
        assertThat(outcome.report().tests().stream().filter(t -> t.test().equals(BACK)).findFirst().orElseThrow().llmCalls())
                .isNotEmpty();
        assertThat(cache.tests.get(OPEN).status()).isEqualTo(TestMigrationStatus.STABLE_PASS);
        assertThat(cache.tests.get(LOGIN_TEST).status()).isEqualTo(TestMigrationStatus.NEEDS_MANUAL_FIX);
        assertThat(generated).hasSize(1);
        assertThat(outcome.report().androidRegressionRun()).isPresent();
        assertThat(outcome.report().stageTimings()).isNotEmpty();
    }

    private void storeSnapshots() {
        for (String screen : List.of("HomePage", "CardsPage", "LoginPage")) {
            ScreenStateKey key = ScreenStateKey.of(new ScreenId(screen), ScreenState.DEFAULT);
            snapshots.put(key, Platform.ANDROID, "<hierarchy><Button text=\"" + screen + "\"/></hierarchy>");
            snapshots.put(key, Platform.IOS, switch (screen) {
                case "HomePage" -> "<hierarchy><Button name=\"cards-tile\" label=\"Cards\"/></hierarchy>";
                case "CardsPage" -> "<hierarchy><StaticText name=\"Visa 1111\"/><StaticText name=\"Visa 4242\"/></hierarchy>";
                default -> "<hierarchy><TextField name=\"Email\"/></hierarchy>";
            });
        }
    }

    private com.project.migration.domain.model.run.TestRunResult runTest(TestMethodRef test, Platform platform) {
        int run = runsPerTest.merge(test.methodName(), 1, Integer::sum);
        boolean fails = test.equals(LOGIN_TEST) || test.equals(BACK) && run == 1;
        return fails ? ScriptedRunner.failed(test, platform, "no such element in run " + run) : ScriptedRunner.passed(test, platform);
    }

    /** One stand-in for every model call of the pipeline, deciding from the purpose. */
    private Object model(LlmRequest<?> request) {
        String purpose = request.purpose();
        if (purpose.startsWith("delta-analysis")) {
            return new DeltaAnalysis(List.of(), List.of());
        }
        if (purpose.startsWith("locator-mapping:HomePage")) {
            return answer("com.bank.pages.HomePage.cardsTile", "accessibility", "cards-tile");
        }
        if (purpose.startsWith("locator-mapping:LoginPage")) {
            return answer(LOGIN + ".emailField", "accessibility", "Email");
        }
        if (purpose.startsWith("locator-mapping:CardsPage")) {
            return answer("com.bank.pages.CardsPage.cardTitles", "iOSNsPredicate", "type == 'XCUIElementTypeStaticText'");
        }
        if (purpose.endsWith("scrollToCard(String)")) {
            return new MethodRewrite("scrollToElementByText(last4);\nreturn this;", List.of(), "");
        }
        if (purpose.contains("backToHome")) {
            return new MethodRewrite("navigateBack();\nreturn new HomePage(driver, platformName).refresh();", List.of(), "");
        }
        if (purpose.startsWith("heal-investigate")) {
            return "the element exists with another name";
        }
        if (purpose.startsWith("heal:") && purpose.contains("backFromCards")) {
            return proposal("com.bank.pages.HomePage", "cardsTile", "iOSNsPredicate",
                    "name == 'cards-tile' AND type == 'XCUIElementTypeButton'");
        }
        if (purpose.startsWith("heal:") && purpose.contains("loginWithOverload")) {
            String attempt = purpose.replace(":repair", "").substring(purpose.replace(":repair", "").lastIndexOf('#') + 1);
            return proposal(LOGIN, "emailField", "iOSNsPredicate", switch (attempt) {
                case "1" -> "name == 'Email'";
                case "2" -> "label == 'Email'";
                default -> "name CONTAINS 'Email'";
            });
        }
        throw new AssertionError("unexpected request " + purpose);
    }

    private static HealingProposal proposal(String targetClass, String field, String strategy, String value) {
        return new HealingProposal("the locator does not match on iOS", HealingRootCause.LOCATOR, "changes the locator",
                List.of(new HealingProposal.Change(ChangeKind.ADD_IOS_LOCATOR, targetClass, null, null, field, strategy, value,
                        null, null, null, null)), null);
    }

    private static LocatorMappingAnswer answer(String field, String strategy, String value) {
        return new LocatorMappingAnswer(List.of(new LocatorMappingAnswer.FieldAnswer(field, true, strategy, value)));
    }
}
