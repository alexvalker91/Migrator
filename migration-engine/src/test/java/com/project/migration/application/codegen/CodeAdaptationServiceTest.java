package com.project.migration.application.codegen;

import com.project.migration.application.delta.DeltaAnalysis;
import com.project.migration.application.delta.DeltaAnalyzerService;
import com.project.migration.domain.exception.CodeModificationException;
import com.project.migration.domain.model.Platform;
import com.project.migration.domain.model.analysis.MigrationTaskDelta;
import com.project.migration.domain.model.analysis.PlatformScope;
import com.project.migration.domain.model.analysis.TestMethodTask;
import com.project.migration.domain.model.cache.ScreenCacheEntry;
import com.project.migration.domain.model.cache.ScreenElementMapping;
import com.project.migration.domain.model.cache.VerificationStatus;
import com.project.migration.domain.model.code.AddIosLocator;
import com.project.migration.domain.model.code.CodeChange;
import com.project.migration.domain.model.code.FieldRef;
import com.project.migration.domain.model.code.IosLocatorStrategy;
import com.project.migration.domain.model.code.MethodRef;
import com.project.migration.domain.model.code.TestMethodRef;
import com.project.migration.domain.model.codegen.CodeAdaptationResult;
import com.project.migration.domain.model.llm.LlmRequest;
import com.project.migration.domain.model.llm.LlmTier;
import com.project.migration.domain.model.screen.ScreenId;
import com.project.migration.domain.model.screen.ScreenState;
import com.project.migration.domain.model.screen.ScreenStateKey;
import com.project.migration.domain.port.CodeModifierPort;
import com.project.migration.infrastructure.ast.JavaParserAstAdapter;
import com.project.migration.testsupport.BankFixture;
import com.project.migration.testsupport.InMemoryCache;
import com.project.migration.testsupport.InMemorySnapshots;
import com.project.migration.testsupport.RecordingLogger;
import com.project.migration.testsupport.ScriptedLlm;
import com.project.migration.testsupport.TelemetryFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class CodeAdaptationServiceTest {

    static final String CARDS_TEST = "com.bank.tests.CardsTest";
    static final String HOME = "com.bank.pages.HomePage";
    static final String CARDS = "com.bank.pages.CardsPage";
    static final ScreenId HOME_SCREEN = new ScreenId("HomePage");
    static final ScreenId CARDS_SCREEN = new ScreenId("CardsPage");
    static final ScreenStateKey HOME_DEFAULT = ScreenStateKey.of(HOME_SCREEN, ScreenState.DEFAULT);
    static final ScreenStateKey CARDS_DEFAULT = ScreenStateKey.of(CARDS_SCREEN, ScreenState.DEFAULT);
    static final Instant NOW = Instant.parse("2026-10-30T10:00:00Z");

    @TempDir
    Path project;

    private final InMemoryCache cache = new InMemoryCache();
    private final InMemorySnapshots snapshots = new InMemorySnapshots();
    private final ScriptedLlm llm = new ScriptedLlm();
    private final RecordingLogger logger = new RecordingLogger();
    private JavaParserAstAdapter analyzer;
    private CodeModifierPort modifier;
    private CodeAdaptationService service;

    @BeforeEach
    void setUp() {
        BankFixture.copyTo(project);
        analyzer = BankFixture.analyzer(project);
        modifier = analyzer.modifier();
        service = new CodeAdaptationService(analyzer, modifier, cache, snapshots, llm, logger,
                Clock.fixed(NOW, ZoneOffset.UTC));
        snapshots.put(HOME_DEFAULT, Platform.ANDROID,
                "<hierarchy><Button content-desc=\"cards-tile\" text=\"Cards\"/></hierarchy>");
        snapshots.put(HOME_DEFAULT, Platform.IOS,
                "<hierarchy><Button name=\"cards-tile\" label=\"Cards\" offscreen=\"true\"/></hierarchy>");
        snapshots.put(CARDS_DEFAULT, Platform.ANDROID, "<hierarchy><TextView text=\"Visa 1111\"/></hierarchy>");
        snapshots.put(CARDS_DEFAULT, Platform.IOS,
                "<hierarchy><StaticText name=\"Visa 1111\"/><StaticText name=\"Visa 4242\"/></hierarchy>");
    }

    @Test
    void turnsTheDeltaIntoValidatedLocatorsRewritesScrollCallsAndGroupsOnDisk() throws IOException {
        MigrationTaskDelta delta = analyzeCardsTest();
        llm.otherwise(request -> switch (kind(request)) {
            case "locator-mapping:HomePage/DEFAULT" -> mapping(field(HOME, "cardsTile", "accessibility", "cards-tile"));
            case "locator-mapping:CardsPage/DEFAULT" -> mapping(field(CARDS, "cardTitles", "iOSNsPredicate",
                    "type == 'XCUIElementTypeStaticText'"));
            case "method-adaptation:com.bank.pages.CardsPage#scrollToCard(String)" -> new MethodRewrite(
                    "scrollToElementByText(last4);\nreturn this;", List.of(), "uses the helper");
            case "method-adaptation:com.bank.pages.CardsPage#backToHome()" -> new MethodRewrite(
                    "navigateBack();\nreturn new HomePage(driver, platformName).refresh();", List.of(), "uses navigateBack");
            default -> throw new AssertionError("unexpected call " + request.purpose());
        });

        CodeAdaptationResult result = service.adapt(delta);

        assertThat(result.mapped()).extracting(m -> m.field().fieldName() + ":" + m.strategy())
                .containsExactlyInAnyOrder("cardsTile:ACCESSIBILITY_ID", "cardTitles:NS_PREDICATE");
        assertThat(result.unresolved()).extracting(u -> u.field().fieldName())
                .containsExactly("emailField");
        assertThat(result.unresolved().getFirst().reason()).contains("no Android/iOS snapshot pair");
        assertThat(result.adaptedMethods()).extracting(MethodRef::methodName)
                .containsExactlyInAnyOrder("scrollToCard", "backToHome");
        assertThat(result.needsManual()).isEmpty();
        assertThat(result.taggedTests()).extracting(TestMethodRef::methodName).containsExactly("payWithGooglePay");
        assertThat(result.modification().changedFiles()).hasSize(3);

        String home = read(HOME, false);
        assertThat(home).contains("@iOSXCUITFindBy(accessibility = \"cards-tile\")");
        assertThat(home).contains("scrollToElementByText(\"Cards\");\n        cardsTile.click();");
        String cards = read(CARDS, false);
        assertThat(cards).contains("@iOSXCUITFindBy(iOSNsPredicate = \"type == 'XCUIElementTypeStaticText'\")");
        assertThat(cards).doesNotContain("UiScrollable").doesNotContain("AndroidKey.BACK");
        assertThat(cards).contains("scrollToElementByText(last4);").contains("navigateBack();");
        assertThat(read(CARDS_TEST, true)).contains("@Test(groups = {\"smoke\", \"android-only\"})");

        ScreenCacheEntry homeEntry = cache.findScreen(HOME_SCREEN).orElseThrow();
        assertThat(homeEntry.mappings()).singleElement().satisfies(mapping -> {
            assertThat(mapping.status()).isEqualTo(VerificationStatus.UNVERIFIED);
            assertThat(mapping.androidLocator()).isEqualTo("accessibility = \"cards-tile\"");
            assertThat(mapping.iosLocator()).isEqualTo("cards-tile");
        });
        assertThat(homeEntry.states()).containsEntry(HOME_DEFAULT, VerificationStatus.UNVERIFIED);
        assertThat(llm.requests().stream().filter(r -> r.purpose().startsWith("method-adaptation")))
                .allSatisfy(r -> {
                    assertThat(r.tier()).isEqualTo(LlmTier.TIER_1);
                    assertThat(r.reasoningRequested()).isTrue();
                });
        assertThat(llm.requests().stream().filter(r -> r.purpose().startsWith("locator-mapping")))
                .allSatisfy(r -> assertThat(r.tier()).isEqualTo(LlmTier.TIER_2));

        CodeAdaptationResult again = service.adapt(delta);
        assertThat(again.modification().hasChanges()).isFalse();
        assertThat(again.taggedTests()).isEmpty();
    }

    @Test
    void repairsARejectedLocatorOnceOnTierTwoThenEscalatesToTierOne() throws IOException {
        MigrationTaskDelta delta = singleFieldDelta(CARDS, "cardTitles");
        llm.thenReturn(mapping(field(CARDS, "cardTitles", "accessibility", "nope")))
                .thenReturn(mapping(field(CARDS, "cardTitles", "iOSNsPredicate", "name == 'a' OR name == 'b'")))
                .thenReturn(mapping(field(CARDS, "cardTitles", "iOSNsPredicate", "type == 'XCUIElementTypeStaticText'")));

        CodeAdaptationResult result = service.adapt(delta);

        assertThat(llm.requests()).extracting(LlmRequest::tier).containsExactly(LlmTier.TIER_2, LlmTier.TIER_2, LlmTier.TIER_1);
        assertThat(llm.requests().get(1).messages().toString()).contains("rejected by the engine").contains("nope")
                .contains("matches no element");
        assertThat(llm.requests().get(2).messages().toString()).contains("OR is not supported");
        assertThat(result.mapped()).singleElement().satisfies(m -> assertThat(m.value()).contains("StaticText"));
        assertThat(result.unresolved()).isEmpty();
    }

    @Test
    void leavesAFieldUnresolvedWithTheLastReasonWhenEveryAttemptIsRejected() {
        MigrationTaskDelta delta = singleFieldDelta(CARDS, "cardTitles");
        llm.otherwise(request -> mapping(field(CARDS, "cardTitles", "accessibility", "nope")));

        CodeAdaptationResult result = service.adapt(delta);

        assertThat(llm.requests()).hasSize(3);
        assertThat(result.mapped()).isEmpty();
        assertThat(result.unresolved()).singleElement().satisfies(u -> {
            assertThat(u.field().fieldName()).isEqualTo("cardTitles");
            assertThat(u.reason()).contains("accessibility").contains("nope").contains("matches no element");
        });
        assertThat(cache.screens).isEmpty();
    }

    @Test
    void looksForAFieldInTheNextStateWhenItIsNotOnTheDefaultOne() {
        ScreenStateKey sheet = new ScreenStateKey(HOME_SCREEN, ScreenState.BOTTOM_SHEET, "picker");
        snapshots.put(HOME_DEFAULT, Platform.IOS, "<hierarchy><Button name=\"other\"/></hierarchy>");
        snapshots.put(sheet, Platform.ANDROID, "<hierarchy><Button content-desc=\"sheet\"/></hierarchy>");
        snapshots.put(sheet, Platform.IOS, "<hierarchy><Button name=\"Cards tile\"/></hierarchy>");
        MigrationTaskDelta delta = singleFieldDelta(HOME, "cardsTile");
        llm.thenReturn(new LocatorMappingAnswer(List.of(
                        new LocatorMappingAnswer.FieldAnswer(HOME + ".cardsTile", false, null, null))))
                .thenReturn(mapping(field(HOME, "cardsTile", "accessibility", "Cards tile")));

        CodeAdaptationResult result = service.adapt(delta);

        assertThat(llm.requests()).extracting(LlmRequest::purpose)
                .containsExactly("locator-mapping:HomePage/DEFAULT", "locator-mapping:HomePage/BOTTOM_SHEET[picker]");
        assertThat(result.mapped()).singleElement().satisfies(m -> assertThat(m.foundIn()).isEqualTo(sheet));
        assertThat(cache.findScreen(HOME_SCREEN).orElseThrow().states()).containsKey(sheet);
    }

    @Test
    void reportsAConflictWhenTheChosenLocatorFailsInAnotherStateWhereAndroidFindsTheElement() {
        ScreenStateKey dialog = new ScreenStateKey(HOME_SCREEN, ScreenState.MODAL_DIALOG, "default");
        snapshots.put(HOME_DEFAULT, Platform.IOS, "<hierarchy><Button name=\"Cards tile\"/></hierarchy>");
        snapshots.put(dialog, Platform.ANDROID, "<hierarchy><Button content-desc=\"cards-tile\"/></hierarchy>");
        snapshots.put(dialog, Platform.IOS, "<hierarchy><Button name=\"OK\"/></hierarchy>");
        MigrationTaskDelta delta = singleFieldDelta(HOME, "cardsTile");
        llm.thenReturn(mapping(field(HOME, "cardsTile", "accessibility", "Cards tile")));

        CodeAdaptationResult result = service.adapt(delta);

        assertThat(result.mapped()).hasSize(1);
        assertThat(result.conflicts()).singleElement().satisfies(conflict -> {
            assertThat(conflict.field().fieldName()).isEqualTo("cardsTile");
            assertThat(conflict.chosenIn()).isEqualTo(HOME_DEFAULT);
            assertThat(conflict.conflictsIn()).isEqualTo(dialog);
            assertThat(conflict.detail()).contains("matches no element");
        });
        assertThat(result.hasOpenIssues()).isTrue();
    }

    @Test
    void keepsAMethodUntouchedAndReportsItWhenTheRewriteIsRejectedTwice() throws IOException {
        String before = read(CARDS, false);
        MigrationTaskDelta delta = new MigrationTaskDelta(CARDS_TEST,
                List.of(task("openCardDetails", List.of(MethodRef.of(CARDS, "scrollToCard", "String")))),
                Set.of(), Set.of(), Set.of(MethodRef.of(CARDS, "scrollToCard", "String")), Set.of(), Set.of(),
                Map.of(CARDS, CARDS_SCREEN));
        llm.thenReturn(new MethodRewrite("swipeUpUntilVisible(last4);\nreturn this;", List.of(), "bad"))
                .thenReturn(new MethodRewrite("new UiScrollable(new UiSelector());\nreturn this;", List.of(), "bad"));

        CodeAdaptationResult result = service.adapt(delta);

        assertThat(llm.requests().get(1).messages().toString()).contains("swipeUpUntilVisible");
        assertThat(result.adaptedMethods()).isEmpty();
        assertThat(result.needsManual()).singleElement().satisfies(manual -> {
            assertThat(manual.method().methodName()).isEqualTo("scrollToCard");
            assertThat(manual.reason()).contains("UI_SCROLLABLE");
        });
        assertThat(read(CARDS, false)).isEqualTo(before);
        assertThat(result.modification().hasChanges()).isFalse();
    }

    @Test
    void appliesTheRestWhenOneChangeOfTheBatchIsRefused() {
        MigrationTaskDelta delta = analyzeCardsTest();
        llm.otherwise(request -> switch (kind(request)) {
            case "locator-mapping:HomePage/DEFAULT" -> mapping(field(HOME, "cardsTile", "accessibility", "cards-tile"));
            case "locator-mapping:CardsPage/DEFAULT" -> mapping(field(CARDS, "cardTitles", "iOSNsPredicate",
                    "type == 'XCUIElementTypeStaticText'"));
            default -> new MethodRewrite("scrollToElementByText(last4);\nreturn this;", List.of(), "ok");
        });
        CodeModifierPort refusing = changes -> {
            if (changes.size() > 1 || changes.stream().anyMatch(this::isCardTitlesLocator)) {
                throw new CodeModificationException("refused for the test");
            }
            return modifier.apply(changes);
        };
        service = new CodeAdaptationService(analyzer, refusing, cache, snapshots, llm, logger, Clock.fixed(NOW, ZoneOffset.UTC));

        CodeAdaptationResult result = service.adapt(delta);

        assertThat(result.mapped()).extracting(m -> m.field().fieldName()).containsExactly("cardsTile");
        assertThat(result.unresolved()).anySatisfy(u -> {
            assertThat(u.field().fieldName()).isEqualTo("cardTitles");
            assertThat(u.reason()).contains("could not be applied").contains("refused for the test");
        });
        assertThat(logger.contains("applying them one by one")).isTrue();
        assertThat(cache.findScreen(CARDS_SCREEN)).isEmpty();
        assertThat(cache.findScreen(HOME_SCREEN)).isPresent();
    }

    @Test
    void doesNothingAndAsksNobodyWhenThereIsNothingToAdapt() {
        CodeAdaptationResult result = service.adapt(MigrationTaskDelta.empty(CARDS_TEST));

        assertThat(result.modification().hasChanges()).isFalse();
        assertThat(result.hasOpenIssues()).isFalse();
        assertThat(llm.requests()).isEmpty();
    }

    private boolean isCardTitlesLocator(CodeChange change) {
        return change instanceof AddIosLocator locator && locator.field().fieldName().equals("cardTitles");
    }

    private MigrationTaskDelta analyzeCardsTest() {
        ScriptedLlm deltaLlm = new ScriptedLlm().thenReturn(new DeltaAnalysis(List.of(), List.of()));
        DeltaAnalyzerService deltaService = new DeltaAnalyzerService(analyzer, cache, snapshots, deltaLlm,
                TelemetryFixture.collector(TelemetryFixture.config(project), logger, CARDS_TEST), logger);
        return deltaService.analyze(CARDS_TEST);
    }

    private MigrationTaskDelta singleFieldDelta(String classFqcn, String fieldName) {
        MethodRef step = classFqcn.equals(CARDS) ? MethodRef.of(CARDS, "firstCardTitle") : MethodRef.of(HOME, "openCards");
        return new MigrationTaskDelta(CARDS_TEST, List.of(task("openCardDetails", List.of(step))), Set.of(),
                Set.of(new FieldRef(classFqcn, fieldName)), Set.of(), Set.of(), Set.of(),
                Map.of(classFqcn, new ScreenId(classFqcn.substring(classFqcn.lastIndexOf('.') + 1))));
    }

    private static TestMethodTask task(String test, List<MethodRef> callTree) {
        return new TestMethodTask(new TestMethodRef(CARDS_TEST, test), callTree, Set.of(), Set.of(), PlatformScope.BOTH);
    }

    private static String kind(LlmRequest<?> request) {
        return request.purpose().replace(":repair", "");
    }

    private static LocatorMappingAnswer.FieldAnswer field(String classFqcn, String name, String strategy, String value) {
        return new LocatorMappingAnswer.FieldAnswer(classFqcn + "." + name, true, strategy, value);
    }

    private static LocatorMappingAnswer mapping(LocatorMappingAnswer.FieldAnswer... fields) {
        return new LocatorMappingAnswer(List.of(fields));
    }

    private String read(String fqcn, boolean test) throws IOException {
        return Files.readString(BankFixture.source(project, fqcn, test));
    }
}
