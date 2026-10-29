package com.project.migration.application.healing;

import com.project.migration.application.pagesource.XmlPageSourceCleaner;
import com.project.migration.application.telemetry.TelemetryCollector;
import com.project.migration.domain.exception.CodeModificationException;
import com.project.migration.domain.model.Platform;
import com.project.migration.domain.model.code.AddIosLocator;
import com.project.migration.domain.model.code.CodeChange;
import com.project.migration.domain.model.code.FieldRef;
import com.project.migration.domain.model.code.IosLocatorStrategy;
import com.project.migration.domain.model.code.TestMethodRef;
import com.project.migration.domain.model.config.EngineConfig;
import com.project.migration.domain.model.llm.LlmRequest;
import com.project.migration.domain.model.report.HealingAttempt;
import com.project.migration.domain.model.report.HealingOutcome;
import com.project.migration.domain.model.run.CompilationError;
import com.project.migration.domain.model.run.CompilationResult;
import com.project.migration.domain.model.run.TestRunResult;
import com.project.migration.domain.model.vcs.CommitId;
import com.project.migration.domain.port.CodeModifierPort;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SelfHealingServiceTest {

    static final String HOME = "com.bank.pages.HomePage";
    static final String CARDS = "com.bank.pages.CardsPage";
    static final String CARDS_TEST = "com.bank.tests.CardsTest";
    static final TestMethodRef TEST = new TestMethodRef(CARDS_TEST, "openCardDetails");
    static final Path SCREENSHOT = Path.of("target/screenshots/openCardDetails_1.png");
    static final Path PAGE_SOURCE = Path.of("target/page-source/openCardDetails_1.xml");
    static final String IOS_FAILURE_XML = "<AppiumAUT><XCUIElementTypeApplication visible=\"true\">"
            + "<XCUIElementTypeButton type=\"XCUIElementTypeButton\" name=\"cards-tile\" label=\"Cards\" visible=\"true\" "
            + "enabled=\"true\"/><XCUIElementTypeStaticText name=\"Visa 1111\" visible=\"true\"/>"
            + "<XCUIElementTypeStaticText name=\"Visa 4242\" visible=\"true\"/></XCUIElementTypeApplication></AppiumAUT>";

    @TempDir
    Path project;

    private final InMemoryCache cache = new InMemoryCache();
    private final InMemorySnapshots snapshots = new InMemorySnapshots();
    private final ScriptedLlm llm = new ScriptedLlm();
    private final ScriptedRunner runner = new ScriptedRunner();
    private final FakeMcps mcps = new FakeMcps("appium_get_page_source", "appium_gesture");
    private final FakeArtifacts artifacts = new FakeArtifacts();
    private final RecordingLogger logger = new RecordingLogger();
    private JavaParserAstAdapter analyzer;
    private SnapshotGit git;
    private SelfHealingService service;
    private CodeModifierPort modifier;

    @BeforeEach
    void setUp() {
        BankFixture.copyTo(project);
        analyzer = BankFixture.analyzer(project);
        modifier = analyzer.modifier();
        git = new SnapshotGit(project);
        artifacts.texts.put(PAGE_SOURCE, IOS_FAILURE_XML);
        artifacts.images.put(SCREENSHOT, new byte[]{1, 2, 3});
        service = serviceWith(modifier);
    }

    private SelfHealingService serviceWith(CodeModifierPort modifierToUse) {
        EngineConfig config = TelemetryFixture.config(project).withFramework(BankFixture.FRAMEWORK);
        TelemetryCollector telemetry = TelemetryFixture.collector(config, logger, CARDS_TEST);
        return new SelfHealingService(analyzer, modifierToUse, runner, git, cache, snapshots, mcps, llm, artifacts,
                new XmlPageSourceCleaner(), telemetry, logger, config, Clock.fixed(Instant.parse("2026-10-30T10:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void investigatesLiveThenAppliesAValidatedLocatorFixAndCompiles() throws IOException {
        llm.thenReturn("The tile is visible; its name is cards-tile.")
                .thenReturn(new ScriptedLlm.Reply(proposal(HealingRootCause.LOCATOR, locatorChange("cardsTile", "accessibility", "cards-tile"),
                        new HealingProposal.LearnedRule("Tile Naming", "tiles are named with a dash")), 200, "thinking"));

        HealingResult result = service.heal(request(1, 0, List.of(), failed("no such element")));

        assertThat(result.status()).isEqualTo(HealingStatus.APPLIED);
        assertThat(result.diagnosis()).isEqualTo("the locator is missing");
        assertThat(result.reasoning()).contains("thinking");
        assertThat(result.appliedChanges()).containsExactly(
                new AddIosLocator(new FieldRef(HOME, "cardsTile"), IosLocatorStrategy.ACCESSIBILITY_ID, "cards-tile"));
        assertThat(Files.readString(BankFixture.source(project, HOME, false)))
                .contains("@iOSXCUITFindBy(accessibility = \"cards-tile\")");
        assertThat(runner.calls).containsExactly("compile");
        assertThat(mcps.ios.restarts).isEqualTo(1);
        assertThat(llm.requests()).extracting(LlmRequest::purpose).containsExactly(
                "heal-investigate:" + TEST.key(), "heal:" + TEST.key() + "#1");
        LlmRequest<?> proposalRequest = llm.requests().get(1);
        assertThat(proposalRequest.reasoningRequested()).isTrue();
        assertThat(proposalRequest.hasImages()).isTrue();
        String prompt = proposalRequest.messages().toString();
        assertThat(prompt).contains("no such element").contains("cards-tile").contains("Findings of the live iOS check")
                .contains("The tile is visible").contains("scrollToCard").contains("user@bank.com");
        assertThat(cache.rules).singleElement().satisfies(rule -> {
            assertThat(rule.id()).isEqualTo("tile-naming");
            assertThat(rule.learnedFrom()).contains(TEST);
        });
    }

    @Test
    void exhaustedTestDataStopsWithoutChangesAndTellsTheModelHowOftenTheTestPassed() {
        llm.thenReturn("nothing special").thenReturn(new HealingProposal("the user has no more transfers left",
                HealingRootCause.TEST_DATA, "none", List.of(), null));

        HealingResult result = service.heal(request(1, 1, List.of(), failed("limit reached")));

        assertThat(result.status()).isEqualTo(HealingStatus.DATA_EXHAUSTED);
        assertThat(result.appliedChanges()).isEmpty();
        assertThat(runner.calls).isEmpty();
        assertThat(git.changedFilesSince(git.head())).isEmpty();
        assertThat(llm.requests().get(1).messages().toString()).contains("Passes of this test in a row before this failure: 1")
                .contains("test data is used up");
    }

    @Test
    void sharedFrameworkAndEnvironmentCausesChangeNothing() {
        llm.thenReturn("x").thenReturn(new HealingProposal("BasePage scroll is wrong", HealingRootCause.SHARED_FRAMEWORK, "n/a", List.of(), null))
                .thenReturn("x").thenReturn(new HealingProposal("session died", HealingRootCause.ENVIRONMENT, "n/a", List.of(), null));

        assertThat(service.heal(request(1, 0, List.of(), failed("a"))).status()).isEqualTo(HealingStatus.SHARED_FRAMEWORK);
        assertThat(service.heal(request(2, 0, List.of(), failed("b"))).status()).isEqualTo(HealingStatus.ENVIRONMENT);
        assertThat(runner.calls).isEmpty();
    }

    @Test
    void aProposalThatTouchesTheFrameworkIsRejectedAndRepairedOnce() throws IOException {
        HealingProposal.Change baseChange = new HealingProposal.Change(ChangeKind.REPLACE_METHOD_BODY,
                "com.bank.framework.BasePage", "navigateBack", List.of(), null, null, null, null, null,
                "driver.navigate().back();", null);
        llm.thenReturn("x")
                .thenReturn(proposal(HealingRootCause.OTHER, baseChange, null))
                .thenReturn(proposal(HealingRootCause.LOCATOR, locatorChange("cardsTile", "accessibility", "cards-tile"), null));

        HealingResult result = service.heal(request(1, 0, List.of(), failed("x")));

        assertThat(result.status()).isEqualTo(HealingStatus.APPLIED);
        assertThat(llm.requests().get(2).messages().toString()).contains("rejected by the engine's checks")
                .contains("may not be changed").contains("com.bank.framework.BasePage");
        assertThat(git.changedFilesSince(git.commits.getFirst().id()))
                .containsExactly(Path.of("app/src/main/java/com/bank/pages/HomePage.java"));
    }

    @Test
    void invalidChangesAreCheckedAgainstTheScreenTheMethodsAndTheBodyRules() {
        List<HealingProposal.Change> bad = List.of(
                locatorChange("cardsTile", "accessibility", "does-not-exist"),
                locatorChange("ghostField", "accessibility", "x"),
                new HealingProposal.Change(ChangeKind.REPLACE_METHOD_BODY, CARDS, "scrollToCard", List.of("String"), null, null,
                        null, null, null, "new UiScrollable(new UiSelector()); return this;", null),
                new HealingProposal.Change(ChangeKind.REPLACE_METHOD_BODY, CARDS, "noSuchMethod", List.of(), null, null, null,
                        null, null, "return;", null),
                new HealingProposal.Change(ChangeKind.ADD_IMPORT, CARDS, null, null, null, null, null, null, null, null, "not a name"));
        llm.thenReturn("x").thenReturn(new HealingProposal("d", HealingRootCause.LOCATOR, "s", bad, null))
                .thenReturn(new HealingProposal("d", HealingRootCause.LOCATOR, "s", bad, null));

        HealingResult result = service.heal(request(1, 0, List.of(), failed("x")));

        assertThat(result.status()).isEqualTo(HealingStatus.NO_VALID_CHANGES);
        String repair = llm.requests().get(2).messages().toString();
        assertThat(repair).contains("matches no element").contains("ghostField is not a locator field")
                .contains("UI_SCROLLABLE").contains("noSuchMethod").contains("not a fully qualified class name");
        assertThat(git.changedFilesSince(git.head())).isEmpty();
        assertThat(runner.calls).isEmpty();
    }

    @Test
    void aCompilationFailureRevertsToTheBaselineAndReplaysEarlierAttempts() throws IOException {
        CommitId baseline = git.head();
        List<CodeChange> first = List.of(new AddIosLocator(new FieldRef(HOME, "cardsTile"),
                IosLocatorStrategy.ACCESSIBILITY_ID, "cards-tile"));
        modifier.apply(first);
        String cardsBefore = Files.readString(BankFixture.source(project, CARDS, false));
        runner.compilations.add(CompilationResult.failed(List.of(new CompilationError(Path.of("CardsPage.java"), 12,
                "cannot find symbol")), Duration.ofSeconds(1)));
        HealingProposal.Change body = new HealingProposal.Change(ChangeKind.REPLACE_METHOD_BODY, CARDS, "scrollToCard",
                List.of("String"), null, null, null, null, null, "scrollToElementByText(last4);\nreturn this;", null);
        llm.thenReturn("x").thenReturn(proposal(HealingRootCause.SCROLL, body, null));

        HealingResult result = service.heal(new HealingRequest(TEST, failed("x"), 2, 0, baseline, List.of(first),
                List.of(attempt(1))));

        assertThat(result.status()).isEqualTo(HealingStatus.COMPILATION_FAILED);
        assertThat(result.changeSummary()).contains("reverted").contains("CardsPage.java:12 cannot find symbol");
        assertThat(Files.readString(BankFixture.source(project, CARDS, false))).isEqualTo(cardsBefore);
        assertThat(Files.readString(BankFixture.source(project, HOME, false)))
                .contains("@iOSXCUITFindBy(accessibility = \"cards-tile\")");
        assertThat(git.restores).hasSize(1);
        assertThat(llm.requests().get(1).messages().toString()).contains("Earlier attempts").contains("first diagnosis");
        assertThat(cache.rules).isEmpty();
    }

    @Test
    void aProposalThatCannotBeAppliedGetsARepairRound() {
        CodeModifierPort refusingOnce = new CodeModifierPort() {
            private boolean refused;

            @Override
            public com.project.migration.domain.model.code.ModificationResult apply(List<CodeChange> changes) {
                if (!refused) {
                    refused = true;
                    throw new CodeModificationException("anchor not found");
                }
                return modifier.apply(changes);
            }
        };
        service = serviceWith(refusingOnce);
        llm.thenReturn("x")
                .thenReturn(proposal(HealingRootCause.LOCATOR, locatorChange("cardsTile", "accessibility", "cards-tile"), null))
                .thenReturn(proposal(HealingRootCause.LOCATOR, locatorChange("cardsTile", "accessibility", "cards-tile"), null));

        HealingResult result = service.heal(request(1, 0, List.of(), failed("x")));

        assertThat(result.status()).isEqualTo(HealingStatus.APPLIED);
        assertThat(llm.requests().get(2).messages().toString()).contains("could not be applied: anchor not found");
    }

    @Test
    void healsWithoutEvidenceFilesAndWithoutALiveSession() {
        mcps.ios.restartFailure = new com.project.migration.domain.exception.McpException(Platform.IOS, "no session");
        TestRunResult noEvidence = ScriptedRunner.failed(TEST, Platform.IOS, "x");
        llm.thenReturn(new HealingProposal("d", HealingRootCause.LOCATOR, "s", List.of(
                locatorChange("cardsTile", "accessibility", "cards-tile")), null));
        snapshots.put(com.project.migration.domain.model.screen.ScreenStateKey.of(
                new com.project.migration.domain.model.screen.ScreenId("HomePage"),
                com.project.migration.domain.model.screen.ScreenState.DEFAULT), Platform.IOS,
                "<hierarchy><Button name=\"cards-tile\"/></hierarchy>");

        HealingResult result = service.heal(request(1, 0, List.of(), noEvidence));

        assertThat(result.status()).isEqualTo(HealingStatus.APPLIED);
        assertThat(logger.contains("Failure evidence of openCardDetails is incomplete")).isTrue();
        assertThat(logger.contains("Live iOS check unavailable")).isTrue();
        assertThat(llm.requests()).hasSize(1);
        assertThat(llm.requests().getFirst().messages().toString()).contains("the live iOS check was not possible")
                .contains("(not available)");
        assertThat(llm.requests().getFirst().hasImages()).isFalse();
    }

    private HealingRequest request(int attempt, int passes, List<List<CodeChange>> batches, TestRunResult run) {
        return new HealingRequest(TEST, run, attempt, passes, git.head(), batches, List.of());
    }

    private static TestRunResult failed(String message) {
        return ScriptedRunner.failed(TEST, Platform.IOS, message)
                .withFailureArtifacts(Optional.of(SCREENSHOT), Optional.of(PAGE_SOURCE));
    }

    private static HealingAttempt attempt(int number) {
        return new HealingAttempt(number, "first diagnosis", Optional.empty(), "first change", HealingOutcome.STILL_FAILING);
    }

    private static HealingProposal proposal(HealingRootCause cause, HealingProposal.Change change, HealingProposal.LearnedRule rule) {
        return new HealingProposal("the locator is missing", cause, "adds the iOS locator", new ArrayList<>(List.of(change)), rule);
    }

    private static HealingProposal.Change locatorChange(String field, String strategy, String value) {
        return new HealingProposal.Change(ChangeKind.ADD_IOS_LOCATOR, HOME, null, null, field, strategy, value, null, null, null, null);
    }
}
