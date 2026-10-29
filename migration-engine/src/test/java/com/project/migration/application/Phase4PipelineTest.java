package com.project.migration.application;

import com.project.migration.application.codegen.CodeAdaptationService;
import com.project.migration.application.codegen.LocatorMappingAnswer;
import com.project.migration.application.codegen.MethodRewrite;
import com.project.migration.application.delta.DeltaAnalysis;
import com.project.migration.application.delta.DeltaAnalyzerService;
import com.project.migration.application.pagesource.XmlPageSourceCleaner;
import com.project.migration.application.snapshot.SnapshotCollectionService;
import com.project.migration.application.snapshot.WalkthroughSummary;
import com.project.migration.application.telemetry.TelemetryCollector;
import com.project.migration.domain.model.Platform;
import com.project.migration.domain.model.analysis.MigrationTaskDelta;
import com.project.migration.domain.model.code.MethodRef;
import com.project.migration.domain.model.codegen.CodeAdaptationResult;
import com.project.migration.domain.model.config.AgentLoopConfig;
import com.project.migration.domain.model.config.EngineConfig;
import com.project.migration.domain.model.llm.LlmRequest;
import com.project.migration.domain.model.llm.ToolResultMessage;
import com.project.migration.domain.model.screen.ScreenId;
import com.project.migration.domain.model.screen.ScreenState;
import com.project.migration.domain.model.screen.ScreenStateKey;
import com.project.migration.domain.model.snapshot.SnapshotCollectionResult;
import com.project.migration.infrastructure.ast.JavaParserAstAdapter;
import com.project.migration.testsupport.BankFixture;
import com.project.migration.testsupport.FakeMcps;
import com.project.migration.testsupport.InMemoryCache;
import com.project.migration.testsupport.InMemorySnapshots;
import com.project.migration.testsupport.RecordingLogger;
import com.project.migration.testsupport.ScriptedLlm;
import com.project.migration.testsupport.TelemetryFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.project.migration.testsupport.ScriptedLlm.call;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage 1 → 2 → 3 chained on the synthetic banking project, with the model and both MCP sessions faked.
 */
class Phase4PipelineTest {

    private static final String CARDS_TEST = "com.bank.tests.CardsTest";
    private static final Pattern WANTED = Pattern.compile("- screen=(\\w+), state=(\\w+), variant=([\\w-]+)");
    private static final String HOME_ANDROID = "<hierarchy><android.widget.Button class=\"android.widget.Button\" "
            + "content-desc=\"cards-tile\" text=\"Cards\" displayed=\"true\"/></hierarchy>";
    private static final String HOME_IOS = "<AppiumAUT><XCUIElementTypeApplication visible=\"true\">"
            + "<XCUIElementTypeButton type=\"XCUIElementTypeButton\" name=\"cards-tile\" label=\"Cards\" visible=\"false\" "
            + "enabled=\"true\"/></XCUIElementTypeApplication></AppiumAUT>";
    private static final String CARDS_ANDROID = "<hierarchy><android.widget.TextView class=\"android.widget.TextView\" "
            + "text=\"Visa 1111\" displayed=\"true\"/></hierarchy>";
    private static final String CARDS_IOS = "<AppiumAUT><XCUIElementTypeApplication visible=\"true\">"
            + "<XCUIElementTypeStaticText name=\"Visa 1111\" visible=\"true\"/>"
            + "<XCUIElementTypeStaticText name=\"Visa 4242\" visible=\"true\"/>"
            + "</XCUIElementTypeApplication></AppiumAUT>";

    @TempDir
    Path project;

    private final FakeMcps mcps = new FakeMcps("appium_get_page_source", "appium_gesture");
    private final InMemorySnapshots snapshots = new InMemorySnapshots();
    private final InMemoryCache cache = new InMemoryCache();
    private final RecordingLogger logger = new RecordingLogger();
    private final ScriptedLlm llm = new ScriptedLlm();

    @Test
    void analyzesCollectsAndAdaptsTheCardsTestClass() throws IOException {
        BankFixture.copyTo(project);
        EngineConfig config = TelemetryFixture.config(project);
        TelemetryCollector telemetry = TelemetryFixture.collector(config, logger, CARDS_TEST);
        JavaParserAstAdapter analyzer = BankFixture.analyzer(project);
        Clock clock = Clock.fixed(Instant.parse("2026-10-30T10:00:00Z"), ZoneOffset.UTC);
        llm.otherwise(this::model);

        MigrationTaskDelta delta = new DeltaAnalyzerService(analyzer, cache, snapshots, llm, telemetry, logger)
                .analyze(CARDS_TEST);
        assertThat(delta.missingSnapshots()).containsExactlyInAnyOrder(
                ScreenStateKey.of(new ScreenId("HomePage"), ScreenState.DEFAULT),
                ScreenStateKey.of(new ScreenId("CardsPage"), ScreenState.DEFAULT),
                ScreenStateKey.of(new ScreenId("LoginPage"), ScreenState.DEFAULT));

        SnapshotCollectionResult collected = new SnapshotCollectionService(mcps, snapshots, llm, analyzer,
                (cls, walkthrough) -> { }, new XmlPageSourceCleaner(), telemetry, logger, AgentLoopConfig.DEFAULT, clock)
                .collect(delta);
        assertThat(collected.captured()).hasSize(3);
        assertThat(collected.unreachable()).isEmpty();
        assertThat(snapshots.stored).hasSize(6);
        assertThat(snapshots.find(ScreenStateKey.of(new ScreenId("HomePage"), ScreenState.DEFAULT), Platform.IOS)
                .orElseThrow().cleanXml()).contains("offscreen=\"true\"").contains("name=\"cards-tile\"");

        CodeAdaptationResult adapted = new CodeAdaptationService(analyzer, analyzer.modifier(), cache, snapshots, llm,
                logger, clock).adapt(delta);

        assertThat(adapted.mapped()).extracting(m -> m.field().fieldName())
                .containsExactlyInAnyOrder("cardsTile", "cardTitles", "emailField");
        assertThat(adapted.unresolved()).isEmpty();
        assertThat(adapted.adaptedMethods()).extracting(MethodRef::methodName)
                .containsExactlyInAnyOrder("scrollToCard", "backToHome");
        assertThat(adapted.needsManual()).isEmpty();
        assertThat(adapted.taggedTests()).extracting(t -> t.methodName()).containsExactly("payWithGooglePay");

        String home = Files.readString(BankFixture.source(project, "com.bank.pages.HomePage", false));
        assertThat(home).contains("@iOSXCUITFindBy(accessibility = \"cards-tile\")")
                .contains("scrollToElementByText(\"Cards\");");
        String cards = Files.readString(BankFixture.source(project, "com.bank.pages.CardsPage", false));
        assertThat(cards).doesNotContain("UiScrollable").contains("navigateBack();");
        assertThat(Files.readString(BankFixture.source(project, CARDS_TEST, true)))
                .contains("groups = {\"smoke\", \"android-only\"}");
        assertThat(cache.findAllScreens()).extracting(e -> e.screen().value())
                .containsExactlyInAnyOrder("HomePage", "CardsPage", "LoginPage");
        assertThat(telemetry.snapshot().savings()).isNotEmpty();
        assertThat(mcps.android.restarts).isEqualTo(mcps.ios.restarts);
    }

    /** Stand-in for all model tiers, deciding from the purpose of each request. */
    private Object model(LlmRequest<?> request) {
        String purpose = request.purpose();
        if (purpose.startsWith("delta-analysis")) {
            return new DeltaAnalysis(List.of(), List.of());
        }
        if (purpose.startsWith("navigate-")) {
            return navigate(request);
        }
        if (purpose.startsWith("walkthrough-summary")) {
            return new WalkthroughSummary(List.of(), List.of());
        }
        if (purpose.startsWith("locator-mapping:HomePage")) {
            return answer("com.bank.pages.HomePage.cardsTile", "accessibility", "cards-tile");
        }
        if (purpose.startsWith("locator-mapping:LoginPage")) {
            return answer("com.bank.pages.LoginPage.emailField", "accessibility", "Email");
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
        throw new AssertionError("unexpected request " + purpose);
    }

    /** A scripted agent: captures the requested states one tool round at a time, then reports. */
    private Object navigate(LlmRequest<?> request) {
        List<String[]> wanted = new ArrayList<>();
        Matcher matcher = WANTED.matcher(request.messages().toString());
        while (matcher.find()) {
            wanted.add(new String[]{matcher.group(1), matcher.group(2), matcher.group(3)});
        }
        long done = request.messages().stream().filter(m -> m instanceof ToolResultMessage).count();
        if (done >= wanted.size()) {
            return "captured everything";
        }
        String[] next = wanted.get((int) done);
        boolean android = request.purpose().startsWith("navigate-android");
        FakeMcps screens = mcps;
        String screen = next[0];
        (android ? screens.android : screens.ios).pageSource = () -> switch (screen) {
            case "HomePage" -> android ? HOME_ANDROID : HOME_IOS;
            case "CardsPage" -> android ? CARDS_ANDROID : CARDS_IOS;
            default -> android ? "<hierarchy><android.widget.EditText class=\"android.widget.EditText\" text=\"Email\" "
                    + "displayed=\"true\"/></hierarchy>" : "<AppiumAUT><XCUIElementTypeTextField name=\"Email\" "
                    + "visible=\"true\"/></AppiumAUT>";
        };
        return ScriptedLlm.ToolCalls.of(call("c" + done, "capture_snapshot",
                "{\"screen\": \"" + next[0] + "\", \"state\": \"" + next[1] + "\", \"variant\": \"" + next[2] + "\"}"));
    }

    private static LocatorMappingAnswer answer(String field, String strategy, String value) {
        return new LocatorMappingAnswer(List.of(new LocatorMappingAnswer.FieldAnswer(field, true, strategy, value)));
    }
}
