package com.project.migration.application.orchestrator;

import com.project.migration.application.codegen.LocatorMappingAnswer;
import com.project.migration.domain.model.Platform;
import com.project.migration.domain.model.analysis.MigrationTaskDelta;
import com.project.migration.domain.model.cache.GlobalComponentEntry;
import com.project.migration.domain.model.cache.VerificationStatus;
import com.project.migration.domain.model.config.EngineConfig;
import com.project.migration.domain.model.screen.ComponentId;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalComponentInitializerTest {

    static final ComponentId NAV = new ComponentId("BottomNavBar");
    static final String BOTTOM_NAV = "com.bank.pages.BottomNav";
    static final ScreenId HOME_SCREEN = new ScreenId("HomePage");

    @TempDir
    Path project;

    private final InMemoryCache cache = new InMemoryCache();
    private final InMemorySnapshots snapshots = new InMemorySnapshots();
    private final ScriptedLlm llm = new ScriptedLlm();
    private final RecordingLogger logger = new RecordingLogger();
    private JavaParserAstAdapter analyzer;
    private GlobalComponentInitializer initializer;

    @BeforeEach
    void setUp() {
        BankFixture.copyTo(project);
        analyzer = BankFixture.analyzer(project);
        EngineConfig config = TelemetryFixture.config(project).withGlobalComponents(Map.of(NAV, BOTTOM_NAV));
        initializer = new GlobalComponentInitializer(analyzer, analyzer.modifier(), cache, snapshots, llm, logger, config,
                Clock.fixed(Instant.parse("2026-10-30T10:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void mapsTheComponentOnTheSnapshotsOfTheClassUnderMigrationAndSavesAnUnverifiedEntry() throws IOException {
        ScreenStateKey home = ScreenStateKey.of(HOME_SCREEN, ScreenState.DEFAULT);
        snapshots.put(home, Platform.ANDROID, "<hierarchy><Button content-desc=\"Payments\"/></hierarchy>");
        snapshots.put(home, Platform.IOS, "<hierarchy><TabBar zone=\"BOTTOM_NAV_BAR\"><Button name=\"Payments\"/></TabBar></hierarchy>");
        llm.thenReturn(new LocatorMappingAnswer(List.of(
                new LocatorMappingAnswer.FieldAnswer(BOTTOM_NAV + ".paymentsTab", true, "accessibility", "Payments"))));

        ComponentInitResult result = initializer.initialize(delta());

        assertThat(result.initialized()).containsExactly(NAV);
        assertThat(result.unresolved()).isEmpty();
        assertThat(Files.readString(BankFixture.source(project, BOTTOM_NAV, false)))
                .contains("@iOSXCUITFindBy(accessibility = \"Payments\")");
        GlobalComponentEntry entry = cache.findGlobalComponent(NAV).orElseThrow();
        assertThat(entry.status()).isEqualTo(VerificationStatus.UNVERIFIED);
        assertThat(entry.mappings()).singleElement().satisfies(m -> {
            assertThat(m.iosLocator()).isEqualTo("Payments");
            assertThat(m.androidLocator()).isEqualTo("accessibility = \"Payments\"");
        });
        assertThat(llm.requests().getFirst().purpose()).isEqualTo("locator-mapping:HomePage/DEFAULT");

        ComponentInitResult again = initializer.initialize(delta());
        assertThat(again.initialized()).isEmpty();
        assertThat(llm.requests()).hasSize(1);
    }

    @Test
    void aComponentWaitsForSnapshotsAndIsNotSavedUntilThereAreSome() {
        ComponentInitResult result = initializer.initialize(delta());

        assertThat(result.initialized()).isEmpty();
        assertThat(cache.components).isEmpty();
        assertThat(llm.requests()).isEmpty();
        assertThat(logger.contains("waits for snapshot pairs")).isTrue();
    }

    @Test
    void fieldsThatCannotBeMappedAreReportedButTheComponentIsStillRecorded() {
        ScreenStateKey home = ScreenStateKey.of(HOME_SCREEN, ScreenState.DEFAULT);
        snapshots.put(home, Platform.ANDROID, "<hierarchy/>");
        snapshots.put(home, Platform.IOS, "<hierarchy><Button name=\"Other\"/></hierarchy>");
        llm.otherwise(request -> new LocatorMappingAnswer(List.of(
                new LocatorMappingAnswer.FieldAnswer(BOTTOM_NAV + ".paymentsTab", false, null, null))));

        ComponentInitResult result = initializer.initialize(delta());

        assertThat(result.initialized()).containsExactly(NAV);
        assertThat(result.unresolved()).singleElement().satisfies(u -> assertThat(u.field().fieldName()).isEqualTo("paymentsTab"));
        assertThat(cache.findGlobalComponent(NAV).orElseThrow().mappings()).isEmpty();
    }

    @Test
    void globalComponentFieldsNeverReachTheDeltaOfAClass() {
        com.project.migration.testsupport.ScriptedLlm deltaLlm = new ScriptedLlm()
                .thenReturn(new com.project.migration.application.delta.DeltaAnalysis(List.of(), List.of()));
        var deltaService = new com.project.migration.application.delta.DeltaAnalyzerService(analyzer, cache, snapshots, deltaLlm,
                TelemetryFixture.collector(TelemetryFixture.config(project), logger, "com.bank.tests.CardsTest"), logger,
                Set.of("com.bank.pages.HomePage"));

        MigrationTaskDelta delta = deltaService.analyze("com.bank.tests.CardsTest");

        assertThat(delta.locatorsToMap()).noneMatch(field -> field.classFqcn().equals("com.bank.pages.HomePage"));
        assertThat(delta.locatorsToMap()).isNotEmpty();
    }

    @Test
    void analyzerListsAllLocatorFieldsOfAClassWithTheirKinds() {
        assertThat(analyzer.listLocatorFields("com.bank.pages.CardsPage")).extracting(f -> f.field().fieldName() + ":" + f.collection())
                .containsExactly("cardTitles:true", "balance:false", "blockCardButton:false", "unblockCardButton:false");
        assertThat(analyzer.listLocatorFields("com.bank.pages.Missing")).isEmpty();
    }

    private static MigrationTaskDelta delta() {
        return new MigrationTaskDelta("com.bank.tests.CardsTest", List.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
                Map.of("com.bank.pages.HomePage", HOME_SCREEN));
    }
}
