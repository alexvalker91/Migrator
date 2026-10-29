package com.project.migration.application.delta;

import com.project.migration.application.telemetry.TelemetryCollector;
import com.project.migration.domain.model.Platform;
import com.project.migration.domain.model.analysis.MigrationTaskDelta;
import com.project.migration.domain.model.analysis.PlatformScope;
import com.project.migration.domain.model.analysis.TestMethodTask;
import com.project.migration.domain.model.cache.ScreenCacheEntry;
import com.project.migration.domain.model.cache.ScreenElementMapping;
import com.project.migration.domain.model.cache.StepCacheEntry;
import com.project.migration.domain.model.cache.TestCacheEntry;
import com.project.migration.domain.model.cache.VerificationStatus;
import com.project.migration.domain.model.code.FieldRef;
import com.project.migration.domain.model.code.IosLocatorStrategy;
import com.project.migration.domain.model.code.MethodRef;
import com.project.migration.domain.model.code.TestMethodRef;
import com.project.migration.domain.model.report.TestMigrationStatus;
import com.project.migration.domain.model.screen.ScreenId;
import com.project.migration.domain.model.screen.ScreenState;
import com.project.migration.domain.model.screen.ScreenStateKey;
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

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeltaAnalyzerServiceTest {

    static final String CARDS_TEST = "com.bank.tests.CardsTest";
    static final String HOME = "com.bank.pages.HomePage";
    static final String CARDS = "com.bank.pages.CardsPage";
    static final String LOGIN = "com.bank.pages.LoginPage";
    static final Instant NOW = Instant.parse("2026-10-30T10:00:00Z");
    static final ScreenId HOME_SCREEN = new ScreenId("HomePage");
    static final ScreenId CARDS_SCREEN = new ScreenId("CardsPage");

    @TempDir
    Path project;

    private final InMemoryCache cache = new InMemoryCache();
    private final InMemorySnapshots snapshots = new InMemorySnapshots();
    private final ScriptedLlm llm = new ScriptedLlm();
    private final RecordingLogger logger = new RecordingLogger();
    private DeltaAnalyzerService service;
    private TelemetryCollector telemetry;

    @BeforeEach
    void setUp() {
        BankFixture.copyTo(project);
        JavaParserAstAdapter analyzer = BankFixture.analyzer(project);
        telemetry = TelemetryFixture.collector(TelemetryFixture.config(project), logger, CARDS_TEST);
        service = new DeltaAnalyzerService(analyzer, cache, snapshots, llm, telemetry, logger);
    }

    @Test
    void buildsTasksScopesAndMissingSnapshotsFromTheCodeAndTheModelAnswer() {
        llm.thenReturn(new DeltaAnalysis(List.of(), List.of(new DeltaAnalysis.TestStates("openCardDetails",
                List.of(new DeltaAnalysis.StateNeed("CardsPage", "loading", "default"))))));

        MigrationTaskDelta delta = service.analyze(CARDS_TEST);

        assertThat(delta.tasks()).extracting(task -> task.test().methodName())
                .containsExactly("openCardDetails", "payWithGooglePay", "backFromCards", "loginWithOverload");
        TestMethodTask open = task(delta, "openCardDetails");
        assertThat(open.scope()).isEqualTo(PlatformScope.BOTH);
        assertThat(open.requiredStates()).contains(ScreenStateKey.of(HOME_SCREEN, ScreenState.DEFAULT),
                ScreenStateKey.of(CARDS_SCREEN, ScreenState.DEFAULT), ScreenStateKey.of(CARDS_SCREEN, ScreenState.LOADING));
        assertThat(open.requiresTier1Adaptation()).isTrue();
        assertThat(task(delta, "payWithGooglePay").scope()).isEqualTo(PlatformScope.ANDROID_ONLY);
        assertThat(task(delta, "payWithGooglePay").requiredStates()).isEmpty();
        assertThat(delta.missingSnapshots()).contains(ScreenStateKey.of(CARDS_SCREEN, ScreenState.LOADING),
                ScreenStateKey.of(HOME_SCREEN, ScreenState.DEFAULT));
        assertThat(delta.locatorsToMap()).extracting(field -> field.fieldName()).contains("cardsTile", "cardTitles");
        assertThat(delta.screenOfClass()).containsEntry(CARDS, CARDS_SCREEN).containsEntry(HOME, HOME_SCREEN);
        assertThat(delta.stepsToAdapt()).contains(MethodRef.of(CARDS, "scrollToCard", "String"));
        assertThat(delta.isFullyCached()).isFalse();
        assertThat(llm.requests()).hasSize(1);
        assertThat(llm.requests().getFirst().tier().name()).isEqualTo("TIER_3");
        String prompt = llm.requests().getFirst().messages().toString();
        assertThat(prompt).contains("openCardDetails").contains("scrollToCard").contains("com.bank.pages.CardsPage");
        assertThat(prompt).doesNotContain("payWithGooglePay");
    }

    @Test
    void doesNotAskTheModelAndFindsNothingToDoWhenEverythingIsVerified() {
        llm.thenReturn(new DeltaAnalysis(List.of(), List.of()));
        MigrationTaskDelta first = service.analyze(CARDS_TEST);
        verifyEverythingIn(first);

        MigrationTaskDelta second = service.analyze(CARDS_TEST);

        assertThat(first.locatorsToMap()).isNotEmpty();
        assertThat(second.locatorsToMap()).isEmpty();
        assertThat(second.stepsToAdapt()).isEmpty();
        assertThat(second.isFullyCached()).isTrue();
        assertThat(second.alreadyVerifiedSteps()).isNotEmpty();
        assertThat(second.alreadyVerifiedScreens()).contains(HOME_SCREEN, CARDS_SCREEN);
        assertThat(llm.requests()).hasSize(1);
    }

    @Test
    void skipsTestsThatAreAlreadyMigrated() {
        cache.saveTest(new TestCacheEntry(new TestMethodRef(CARDS_TEST, "backFromCards"),
                TestMigrationStatus.STABLE_PASS, NOW));
        cache.saveTest(new TestCacheEntry(new TestMethodRef(CARDS_TEST, "loginWithOverload"),
                TestMigrationStatus.NEEDS_MANUAL_FIX, NOW));
        llm.thenReturn(new DeltaAnalysis(List.of(), List.of()));

        MigrationTaskDelta delta = service.analyze(CARDS_TEST);

        assertThat(delta.tasks()).extracting(task -> task.test().methodName())
                .containsExactly("openCardDetails", "payWithGooglePay", "loginWithOverload");
        assertThat(logger.contains("backFromCards is already migrated")).isTrue();
    }

    @Test
    void doesNotListStatesWhoseSnapshotPairAlreadyExists() {
        ScreenStateKey homeDefault = ScreenStateKey.of(HOME_SCREEN, ScreenState.DEFAULT);
        snapshots.put(homeDefault, Platform.ANDROID, "<a/>");
        snapshots.put(homeDefault, Platform.IOS, "<i/>");
        ScreenStateKey cardsDefault = ScreenStateKey.of(CARDS_SCREEN, ScreenState.DEFAULT);
        snapshots.put(cardsDefault, Platform.ANDROID, "<a/>");
        llm.thenReturn(new DeltaAnalysis(List.of(), List.of()));

        MigrationTaskDelta delta = service.analyze(CARDS_TEST);

        assertThat(delta.missingSnapshots()).doesNotContain(homeDefault).contains(cardsDefault);
    }

    @Test
    void repairsAnInvalidModelAnswerOnceAndKeepsOnlyValidEntries() {
        llm.thenReturn(new DeltaAnalysis(
                        List.of(new DeltaAnalysis.ScreenAssignment("com.bank.pages.Nope", "X")),
                        List.of(new DeltaAnalysis.TestStates("openCardDetails",
                                List.of(new DeltaAnalysis.StateNeed("CardsPage", "WEIRD", "default"))))))
                .thenReturn(new DeltaAnalysis(
                        List.of(new DeltaAnalysis.ScreenAssignment(CARDS, "Cards")),
                        List.of(new DeltaAnalysis.TestStates("openCardDetails", List.of(
                                new DeltaAnalysis.StateNeed("Cards", "BOTTOM_SHEET", "card_picker"),
                                new DeltaAnalysis.StateNeed("Ghost", "DEFAULT", "default"))))));

        MigrationTaskDelta delta = service.analyze(CARDS_TEST);

        assertThat(llm.requests()).hasSize(2);
        assertThat(llm.requests().get(1).messages().toString()).contains("previous answer had these problems")
                .contains("WEIRD").contains("Nope");
        assertThat(delta.screenOfClass()).containsEntry(CARDS, new ScreenId("Cards"));
        assertThat(task(delta, "openCardDetails").requiredStates()).contains(
                new ScreenStateKey(new ScreenId("Cards"), ScreenState.BOTTOM_SHEET, "card_picker"));
        assertThat(task(delta, "openCardDetails").requiredStates()).noneMatch(key -> key.screen().value().equals("Ghost"));
        assertThat(logger.contains("dropped after repair")).isTrue();
    }

    @Test
    void aScreenKnownFromTheCacheWinsOverTheModelAssignment() {
        cache.saveScreen(ScreenCacheEntry.empty(new ScreenId("Cards"), NOW).withMapping(
                new ScreenElementMapping(new FieldRef(CARDS, "balance"), "id = \"x\"",
                        IosLocatorStrategy.ACCESSIBILITY_ID, "balance", VerificationStatus.UNVERIFIED), NOW));
        llm.thenReturn(new DeltaAnalysis(List.of(new DeltaAnalysis.ScreenAssignment(CARDS, "Other")), List.of()));

        MigrationTaskDelta delta = service.analyze(CARDS_TEST);

        assertThat(delta.screenOfClass()).containsEntry(CARDS, new ScreenId("Cards"));
        assertThat(llm.requests().getFirst().messages().toString()).contains("Known screens: Cards");
    }

    @Test
    void unknownClassYieldsAnEmptyDelta() {
        MigrationTaskDelta delta = service.analyze("com.bank.tests.Missing");

        assertThat(delta.tasks()).isEmpty();
        assertThat(delta.isFullyCached()).isTrue();
        assertThat(logger.contains("No @Test methods")).isTrue();
        assertThatThrownBy(() -> new ScreenId("bad name")).isInstanceOf(IllegalArgumentException.class);
    }

    private void verifyEverythingIn(MigrationTaskDelta delta) {
        TestMethodRef by = new TestMethodRef(CARDS_TEST, "openCardDetails");
        delta.stepsToAdapt().forEach(step -> cache.saveStep(StepCacheEntry.verified(step, by, NOW)));
        for (ScreenId screen : delta.screenOfClass().values().stream().distinct().toList()) {
            ScreenCacheEntry entry = ScreenCacheEntry.empty(screen, NOW);
            for (FieldRef field : delta.locatorsToMap()) {
                if (delta.screenOfClass().get(field.classFqcn()).equals(screen)) {
                    entry = entry.withMapping(new ScreenElementMapping(field, "id = \"x\"",
                            IosLocatorStrategy.ACCESSIBILITY_ID, field.fieldName(), VerificationStatus.VERIFIED_STABLE), NOW);
                }
            }
            cache.saveScreen(entry.withState(ScreenStateKey.of(screen, ScreenState.DEFAULT),
                    VerificationStatus.VERIFIED_STABLE, NOW));
        }
    }

    private static TestMethodTask task(MigrationTaskDelta delta, String method) {
        return delta.tasks().stream().filter(task -> task.test().methodName().equals(method)).findFirst().orElseThrow();
    }
}
