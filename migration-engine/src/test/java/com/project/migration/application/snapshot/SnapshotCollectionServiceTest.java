package com.project.migration.application.snapshot;

import com.project.migration.application.pagesource.XmlPageSourceCleaner;
import com.project.migration.application.telemetry.TelemetryCollector;
import com.project.migration.domain.exception.McpException;
import com.project.migration.domain.model.Platform;
import com.project.migration.domain.model.agent.AgentRunOutcome;
import com.project.migration.domain.model.agent.AndroidWalkthrough;
import com.project.migration.domain.model.agent.WalkthroughAction;
import com.project.migration.domain.model.analysis.MigrationTaskDelta;
import com.project.migration.domain.model.analysis.PlatformScope;
import com.project.migration.domain.model.analysis.TestMethodTask;
import com.project.migration.domain.model.code.MethodRef;
import com.project.migration.domain.model.code.TestMethodRef;
import com.project.migration.domain.model.config.AgentLoopConfig;
import com.project.migration.domain.model.config.EngineConfig;
import com.project.migration.domain.model.llm.LlmMessage;
import com.project.migration.domain.model.llm.ToolResultMessage;
import com.project.migration.domain.model.screen.ScreenId;
import com.project.migration.domain.model.screen.ScreenState;
import com.project.migration.domain.model.screen.ScreenStateKey;
import com.project.migration.domain.model.snapshot.SnapshotCollectionResult;
import com.project.migration.domain.model.snapshot.UnreachableReason;
import com.project.migration.infrastructure.ast.JavaParserAstAdapter;
import com.project.migration.testsupport.BankFixture;
import com.project.migration.testsupport.FakeMcps;
import com.project.migration.testsupport.InMemorySnapshots;
import com.project.migration.testsupport.RecordingLogger;
import com.project.migration.testsupport.ScriptedLlm;
import com.project.migration.testsupport.TelemetryFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.project.migration.testsupport.ScriptedLlm.call;
import static org.assertj.core.api.Assertions.assertThat;

class SnapshotCollectionServiceTest {

    static final String CARDS_TEST = "com.bank.tests.CardsTest";
    static final ScreenStateKey HOME = ScreenStateKey.of(new ScreenId("HomePage"), ScreenState.DEFAULT);
    static final ScreenStateKey CARDS = ScreenStateKey.of(new ScreenId("CardsPage"), ScreenState.DEFAULT);
    static final ScreenStateKey SHEET = new ScreenStateKey(new ScreenId("CardsPage"), ScreenState.BOTTOM_SHEET, "picker");
    static final String ANDROID_SCREEN = """
            <hierarchy rotation="0" width="1080" height="2400">
              <android.widget.Button class="android.widget.Button" text="Cards" content-desc="cards-tile"
                  bounds="[1,2][3,4]" displayed="true" enabled="true"/>
            </hierarchy>""";
    static final String IOS_SCREEN = """
            <AppiumAUT>
              <XCUIElementTypeApplication type="XCUIElementTypeApplication" name="Bank" visible="true">
                <XCUIElementTypeButton type="XCUIElementTypeButton" name="cards-tile" label="Cards" visible="true"
                    enabled="true" x="1" y="2" width="3" height="4"/>
              </XCUIElementTypeApplication>
            </AppiumAUT>""";

    @TempDir
    Path project;

    private final FakeMcps mcps = new FakeMcps("appium_get_page_source", "appium_gesture", "appium_session_management");
    private final InMemorySnapshots snapshots = new InMemorySnapshots();
    private final ScriptedLlm llm = new ScriptedLlm();
    private final RecordingLogger logger = new RecordingLogger();
    private final List<AndroidWalkthrough> savedWalkthroughs = new ArrayList<>();
    private SnapshotCollectionService service;
    private TelemetryCollector telemetry;

    @BeforeEach
    void setUp() {
        BankFixture.copyTo(project);
        mcps.android.pageSource = () -> ANDROID_SCREEN;
        mcps.ios.pageSource = () -> IOS_SCREEN;
        service = serviceWith(AgentLoopConfig.DEFAULT);
    }

    private SnapshotCollectionService serviceWith(AgentLoopConfig loopConfig) {
        EngineConfig config = TelemetryFixture.config(project);
        telemetry = TelemetryFixture.collector(config, logger, CARDS_TEST);
        JavaParserAstAdapter analyzer = BankFixture.analyzer(project);
        return new SnapshotCollectionService(mcps, snapshots, llm, analyzer,
                (cls, walkthrough) -> savedWalkthroughs.add(walkthrough), new XmlPageSourceCleaner(), telemetry, logger,
                loopConfig, Clock.fixed(Instant.parse("2026-10-30T10:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void capturesTheStatesOnAndroidThenRepeatsTheWalkthroughOnIosAndStoresPairs() {
        scriptAndroid(capture("HomePage", "DEFAULT"), capture("CardsPage", "BAD_STATE"), capture("CardsPage", "DEFAULT"));
        llm.thenReturn(new WalkthroughSummary(
                List.of(new WalkthroughSummary.Action("tap", "cards-tile", null),
                        new WalkthroughSummary.Action("dance", "x", "y")),
                List.of(new WalkthroughSummary.Observation("HomePage", "DEFAULT", "default", List.of("Cards")),
                        new WalkthroughSummary.Observation("Other", "DEFAULT", "default", List.of("dropped")))));
        scriptIos(capture("HomePage", "DEFAULT"), capture("CardsPage", "DEFAULT"));

        SnapshotCollectionResult result = service.collect(delta(Set.of(HOME, CARDS), Set.of(HOME, CARDS)));

        assertThat(result.captured()).containsExactlyInAnyOrder(HOME, CARDS);
        assertThat(result.isComplete()).isTrue();
        assertThat(result.runs()).extracting(run -> run.platform() + ":" + run.outcome())
                .containsExactly("ANDROID:COMPLETED", "IOS:COMPLETED");
        assertThat(snapshots.stored).hasSize(4);
        String androidXml = snapshots.find(HOME, Platform.ANDROID).orElseThrow().cleanXml();
        assertThat(androidXml).contains("content-desc=\"cards-tile\"").doesNotContain("bounds");
        assertThat(snapshots.find(HOME, Platform.IOS).orElseThrow().cleanXml()).contains("name=\"cards-tile\"")
                .doesNotContain("width");
        assertThat(snapshots.find(HOME, Platform.IOS).orElseThrow().screenshot()).isPresent();
        assertThat(mcps.android.restarts).isEqualTo(1);
        assertThat(mcps.ios.restarts).isEqualTo(1);
        assertThat(telemetry.snapshot().savings()).isNotEmpty();

        AndroidWalkthrough walkthrough = savedWalkthroughs.getFirst();
        assertThat(walkthrough.steps()).extracting(step -> step.action() + ":" + step.target())
                .containsExactly("TAP:cards-tile", "OTHER:x");
        assertThat(walkthrough.observations()).singleElement().satisfies(observation -> {
            assertThat(observation.key()).isEqualTo(HOME);
            assertThat(observation.visibleTexts()).containsExactly("Cards");
        });
        String androidPrompt = llm.requests().getFirst().messages().toString();
        assertThat(androidPrompt).contains("user@bank.com").contains("openCardDetails").contains("screen=CardsPage")
                .contains("reference pass");
        String badCall = toolResults(llm.requests().get(2)).get(1).content();
        assertThat(badCall).contains("Arguments must be a JSON object");
        String iosPrompt = llm.requests().get(5).messages().toString();
        assertThat(iosPrompt).contains("Recorded Android walkthrough").contains("TAP 'cards-tile'")
                .contains("Android showed in HomePage/DEFAULT").contains("Repeat its actions on iOS");
        assertThat(llm.remaining()).isZero();
    }

    @Test
    void anAndroidPassThatReachesNothingEndsTheTestWithoutAnIosPass() {
        AgentLoopConfig tight = new AgentLoopConfig(2, 8, 1_000_000, Duration.ofSeconds(60), 12_000,
                AgentLoopConfig.DEFAULT_ALLOWED_TOOLS);
        service = serviceWith(tight);
        scriptAndroid(call("1", "appium_gesture", "{}"), call("2", "appium_gesture", "{}"));

        SnapshotCollectionResult result = service.collect(delta(Set.of(HOME), Set.of(HOME)));

        assertThat(result.captured()).isEmpty();
        assertThat(result.unreachable()).singleElement().satisfies(state -> {
            assertThat(state.key()).isEqualTo(HOME);
            assertThat(state.platform()).isEqualTo(Platform.ANDROID);
            assertThat(state.reason()).isEqualTo(UnreachableReason.BUDGET_EXHAUSTED);
        });
        assertThat(result.runs()).singleElement().satisfies(run -> assertThat(run.outcome()).isEqualTo(AgentRunOutcome.BUDGET_EXHAUSTED));
        assertThat(mcps.ios.restarts).isZero();
        assertThat(snapshots.stored).isEmpty();
    }

    @Test
    void aStateTheIosPassDoesNotReachStaysUnreachableButTheRestIsKept() {
        scriptAndroid(capture("HomePage", "DEFAULT"), capture("CardsPage", "DEFAULT"));
        llm.thenReturn(new WalkthroughSummary(List.of(), List.of()));
        scriptIos(capture("HomePage", "DEFAULT"));

        SnapshotCollectionResult result = service.collect(delta(Set.of(HOME, CARDS), Set.of(HOME, CARDS)));

        assertThat(result.captured()).containsExactly(HOME);
        assertThat(result.unreachable()).singleElement().satisfies(state -> {
            assertThat(state.key()).isEqualTo(CARDS);
            assertThat(state.platform()).isEqualTo(Platform.IOS);
            assertThat(state.reason()).isEqualTo(UnreachableReason.NOT_REACHED);
            assertThat(state.detail()).isEqualTo("finished");
        });
    }

    @Test
    void anMcpFailureOnIosIsReportedAsMcpErrorAndAndroidSnapshotsRemain() {
        scriptAndroid(capture("HomePage", "DEFAULT"));
        llm.thenReturn(new WalkthroughSummary(List.of(), List.of()));
        mcps.ios.restartFailure = new McpException(Platform.IOS, "session lost");

        SnapshotCollectionResult result = service.collect(delta(Set.of(HOME), Set.of(HOME)));

        assertThat(result.captured()).isEmpty();
        assertThat(result.unreachable()).singleElement().satisfies(state -> {
            assertThat(state.platform()).isEqualTo(Platform.IOS);
            assertThat(state.reason()).isEqualTo(UnreachableReason.MCP_ERROR);
            assertThat(state.detail()).contains("session lost");
        });
        assertThat(snapshots.exists(HOME, Platform.ANDROID)).isTrue();
    }

    @Test
    void aLaterTestRetriesAStateAnEarlierTestMissed() {
        TestMethodRef first = new TestMethodRef(CARDS_TEST, "openCardDetails");
        TestMethodRef second = new TestMethodRef(CARDS_TEST, "backFromCards");
        MigrationTaskDelta delta = new MigrationTaskDelta(CARDS_TEST,
                List.of(task(first, Set.of(HOME, SHEET)), task(second, Set.of(SHEET))),
                Set.of(HOME, SHEET), Set.of(), Set.of(), Set.of(), Set.of(), Map.of());
        scriptAndroid(capture("HomePage", "DEFAULT"));                         // test 1: sheet not reached on Android
        llm.thenReturn(new WalkthroughSummary(List.of(), List.of()));
        scriptIos(capture("HomePage", "DEFAULT"));
        scriptAndroid(capture("CardsPage", "BOTTOM_SHEET", "picker"));         // test 2: reached
        llm.thenReturn(new WalkthroughSummary(List.of(), List.of()));
        scriptIos(capture("CardsPage", "BOTTOM_SHEET", "picker"));

        SnapshotCollectionResult result = service.collect(delta);

        assertThat(result.captured()).containsExactlyInAnyOrder(HOME, SHEET);
        assertThat(result.unreachable()).isEmpty();
        assertThat(mcps.android.restarts).isEqualTo(2);
    }

    @Test
    void neverOverwritesAnExistingSnapshotAndCountsItAsCaptured() {
        snapshots.put(HOME, Platform.ANDROID, "<existing/>");
        scriptAndroid(capture("HomePage", "DEFAULT"));
        llm.thenReturn(new WalkthroughSummary(List.of(), List.of()));
        scriptIos(capture("HomePage", "DEFAULT"));

        SnapshotCollectionResult result = service.collect(delta(Set.of(HOME), Set.of(HOME)));

        assertThat(result.captured()).containsExactly(HOME);
        assertThat(snapshots.find(HOME, Platform.ANDROID).orElseThrow().cleanXml()).isEqualTo("<existing/>");
        assertThat(snapshots.saves).isEqualTo(1);
        assertThat(toolResults(llm.requests().get(1)).getFirst().content()).contains("already stored");
    }

    @Test
    void refusesToCaptureAStateThatWasNotRequested() {
        scriptAndroid(capture("HomePage", "LOADING"), capture("HomePage", "DEFAULT"));
        llm.thenReturn(new WalkthroughSummary(List.of(), List.of()));
        scriptIos(capture("HomePage", "DEFAULT"));

        SnapshotCollectionResult result = service.collect(delta(Set.of(HOME), Set.of(HOME)));

        assertThat(result.captured()).containsExactly(HOME);
        assertThat(toolResults(llm.requests().get(1)).getFirst().content()).contains("was not requested");
        assertThat(snapshots.stored.keySet()).extracting(InMemorySnapshots.Slot::key).containsOnly(HOME);
    }

    @Test
    void doesNothingWhenNoSnapshotIsMissing() {
        SnapshotCollectionResult result = service.collect(delta(Set.of(HOME), Set.of()));

        assertThat(result.captured()).isEmpty();
        assertThat(result.isComplete()).isTrue();
        assertThat(mcps.android.restarts).isZero();
        assertThat(llm.requests()).isEmpty();
    }

    private void scriptAndroid(Object... steps) {
        script(steps);
    }

    private void scriptIos(Object... steps) {
        script(steps);
    }

    /** One model turn per tool call, then the final answer. */
    private void script(Object... steps) {
        for (Object step : steps) {
            llm.thenReturn(ScriptedLlm.ToolCalls.of((com.project.migration.domain.model.llm.ToolCall) step));
        }
        llm.thenReturn("finished");
    }

    private int ids;

    private com.project.migration.domain.model.llm.ToolCall capture(String screen, String state) {
        return capture(screen, state, "default");
    }

    private com.project.migration.domain.model.llm.ToolCall capture(String screen, String state, String variant) {
        return call("c" + ids++, "capture_snapshot", "{\"screen\": \"" + screen + "\", \"state\": \"" + state
                + "\", \"variant\": \"" + variant + "\"}");
    }

    private static List<ToolResultMessage> toolResults(com.project.migration.domain.model.llm.LlmRequest<?> request) {
        List<ToolResultMessage> results = new ArrayList<>();
        for (LlmMessage message : request.messages()) {
            if (message instanceof ToolResultMessage result) {
                results.add(result);
            }
        }
        return results;
    }

    private static MigrationTaskDelta delta(Set<ScreenStateKey> required, Set<ScreenStateKey> missing) {
        return new MigrationTaskDelta(CARDS_TEST,
                List.of(task(new TestMethodRef(CARDS_TEST, "openCardDetails"), required)),
                missing, Set.of(), Set.of(), Set.of(), Set.of(), Map.of());
    }

    private static TestMethodTask task(TestMethodRef test, Set<ScreenStateKey> required) {
        return new TestMethodTask(test, List.of(MethodRef.of("com.bank.pages.HomePage", "openCards")),
                new LinkedHashSet<>(required), Set.of(), PlatformScope.BOTH);
    }
}
