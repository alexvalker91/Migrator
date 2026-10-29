package com.project.migration.application.agent;

import com.project.migration.application.pagesource.XmlPageSourceCleaner;
import com.project.migration.application.telemetry.TelemetryCollector;
import com.project.migration.domain.model.MigrationStage;
import com.project.migration.domain.model.Platform;
import com.project.migration.domain.model.agent.AgentRunOutcome;
import com.project.migration.domain.model.config.AgentLoopConfig;
import com.project.migration.domain.model.config.EngineConfig;
import com.project.migration.domain.model.llm.LlmMessage;
import com.project.migration.domain.model.llm.LlmTier;
import com.project.migration.domain.model.llm.ToolResultMessage;
import com.project.migration.domain.model.llm.ToolSpec;
import com.project.migration.domain.model.mcp.McpToolResult;
import com.project.migration.testsupport.FakeMcp;
import com.project.migration.testsupport.RecordingLogger;
import com.project.migration.testsupport.ScriptedLlm;
import com.project.migration.testsupport.TelemetryFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static com.project.migration.testsupport.ScriptedLlm.call;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentLoopTest {

    private static final String SCREEN = """
            <hierarchy index="0" class="hierarchy" rotation="0" width="1080" height="2400">
              <android.widget.FrameLayout class="android.widget.FrameLayout" bounds="[0,0][1080,2400]" displayed="true">
                <android.widget.Button class="android.widget.Button" text="Login" resource-id="com.bank:id/login"
                    bounds="[1,2][3,4]" displayed="true" enabled="true" index="0"/>
              </android.widget.FrameLayout>
            </hierarchy>""";

    private final RecordingLogger logger = new RecordingLogger();
    private final ScriptedLlm llm = new ScriptedLlm();
    private final FakeMcp mcp = new FakeMcp(Platform.ANDROID, "appium_find_element", "appium_get_page_source",
            "appium_gesture", "appium_session_management", "appium_generate_tests");
    private EngineConfig config;
    private TelemetryCollector telemetry;

    @BeforeEach
    void setUp() {
        config = TelemetryFixture.config(Path.of("/work"));
        telemetry = TelemetryFixture.collector(config, logger, "com.bank.CardsTest");
        mcp.pageSource = () -> SCREEN;
    }

    @Test
    void runsToolRoundsUntilTheModelAnswersWithText() {
        llm.thenReturn(ScriptedLlm.ToolCalls.of(call("1", "appium_find_element", "{\"selector\": \"login\"}")))
                .thenReturn("Reached the login screen.");

        AgentRunResult result = loop(AgentLoopConfig.DEFAULT).run(task(), toolbox(AgentLoopConfig.DEFAULT));

        assertThat(result.outcome()).isEqualTo(AgentRunOutcome.COMPLETED);
        assertThat(result.steps()).isEqualTo(1);
        assertThat(result.tokens()).isEqualTo(200);
        assertThat(result.finalText()).isEqualTo("Reached the login screen.");
        assertThat(result.trace()).singleElement().satisfies(trace -> {
            assertThat(trace.tool()).isEqualTo("appium_find_element");
            assertThat(trace.error()).isFalse();
        });
        assertThat(mcp.invocations).containsExactly("appium_find_element {\"selector\": \"login\"}");
        assertThat(llm.requests().get(0).tier()).isEqualTo(LlmTier.TIER_2);
        assertThat(llm.requests().get(1).messages()).hasAtLeastOneElementOfType(ToolResultMessage.class);
    }

    @Test
    void offersOnlyWhitelistedToolsAndRefusesTheRest() {
        llm.thenReturn(ScriptedLlm.ToolCalls.of(call("1", "appium_session_management", "{\"action\": \"delete\"}")))
                .thenReturn("gave up");

        AgentRunResult result = loop(AgentLoopConfig.DEFAULT).run(task(), toolbox(AgentLoopConfig.DEFAULT));

        assertThat(llm.requests().getFirst().tools()).extracting(ToolSpec::name)
                .containsExactly("appium_find_element", "appium_get_page_source", "appium_gesture");
        assertThat(mcp.invocations).isEmpty();
        assertThat(result.trace().getFirst().error()).isTrue();
        assertThat(toolResults(llm.requests().get(1)).getFirst().content()).contains("not available");
    }

    @Test
    void answersPageSourceFromTheCleanerAndRecordsTheSavings() {
        llm.thenReturn(ScriptedLlm.ToolCalls.of(call("1", "appium_get_page_source", "{}"))).thenReturn("done");

        loop(AgentLoopConfig.DEFAULT).run(task(), toolbox(AgentLoopConfig.DEFAULT));

        String screen = toolResults(llm.requests().get(1)).getFirst().content();
        assertThat(screen).contains("Button").contains("text=\"Login\"").doesNotContain("bounds").doesNotContain("index=");
        assertThat(mcp.invocations).containsExactly("getPageSource");
        assertThat(telemetry.snapshot().savings()).isNotEmpty();
    }

    @Test
    void keepsOnlyTheLatestTwoScreensInTheConversation() {
        for (int i = 1; i <= 3; i++) {
            llm.thenReturn(ScriptedLlm.ToolCalls.of(call("c" + i, "appium_get_page_source", "{}")));
        }
        llm.thenReturn("done");

        loop(AgentLoopConfig.DEFAULT).run(task(), toolbox(AgentLoopConfig.DEFAULT));

        List<ToolResultMessage> results = toolResults(llm.requests().get(3));
        assertThat(results).hasSize(3);
        assertThat(results.get(0).content()).isEqualTo(AgentLoop.OMITTED_SCREEN);
        assertThat(results.get(1).content()).contains("Button");
        assertThat(results.get(2).content()).contains("Button");
    }

    @Test
    void cutsLongToolResultsAndMentionsImages() {
        mcp.handler = (tool, args) -> new McpToolResult("x".repeat(500), List.of(
                com.project.migration.domain.model.media.ImageAttachment.png(FakeMcp.PNG)), false);
        AgentLoopConfig small = new AgentLoopConfig(30, 8, 150_000, Duration.ofSeconds(60), 100,
                AgentLoopConfig.DEFAULT_ALLOWED_TOOLS);
        llm.thenReturn(ScriptedLlm.ToolCalls.of(call("1", "appium_find_element", "{}"))).thenReturn("done");

        loop(small).run(task(), toolbox(small));

        assertThat(toolResults(llm.requests().get(1)).getFirst().content())
                .startsWith("x".repeat(100)).contains("400 more characters").endsWith("[1 image(s) omitted]");
    }

    @Test
    void endsWithBudgetExhaustedAfterTheStepLimit() {
        AgentLoopConfig limits = limits(2, 8, 1_000_000);
        llm.thenReturn(ScriptedLlm.ToolCalls.of(call("1", "appium_gesture", "{}")))
                .thenReturn(ScriptedLlm.ToolCalls.of(call("2", "appium_gesture", "{}")));

        AgentRunResult result = loop(limits).run(task(), toolbox(limits));

        assertThat(result.outcome()).isEqualTo(AgentRunOutcome.BUDGET_EXHAUSTED);
        assertThat(result.steps()).isEqualTo(2);
        assertThat(llm.remaining()).isZero();
    }

    @Test
    void endsWithBudgetExhaustedAfterTheTokenLimit() {
        AgentLoopConfig limits = limits(30, 8, 250);
        llm.thenReturn(ScriptedLlm.ToolCalls.of(call("1", "appium_gesture", "{}")).withTokens(300));

        AgentRunResult result = loop(limits).run(task(), toolbox(limits));

        assertThat(result.outcome()).isEqualTo(AgentRunOutcome.BUDGET_EXHAUSTED);
        assertThat(result.tokens()).isEqualTo(300);
    }

    @Test
    void endsAsStuckAfterTooManyRoundsWithoutProgress() {
        AgentLoopConfig limits = limits(30, 3, 1_000_000);
        for (int i = 0; i < 3; i++) {
            llm.thenReturn(ScriptedLlm.ToolCalls.of(call("c" + i, "appium_gesture", "{}")));
        }

        AgentRunResult result = loop(limits).run(task(), toolbox(limits));

        assertThat(result.outcome()).isEqualTo(AgentRunOutcome.STUCK);
        assertThat(result.steps()).isEqualTo(3);
    }

    @Test
    void progressResetsTheStuckCounter() {
        AgentLoopConfig limits = limits(30, 2, 1_000_000);
        AgentToolbox toolbox = toolbox(limits).with(new SyntheticTool(
                new ToolSpec("mark", "progress", "{\"type\":\"object\"}"), args -> ToolOutcome.progress("marked")));
        llm.thenReturn(ScriptedLlm.ToolCalls.of(call("1", "appium_gesture", "{}")))
                .thenReturn(ScriptedLlm.ToolCalls.of(call("2", "mark", "{}")))
                .thenReturn(ScriptedLlm.ToolCalls.of(call("3", "appium_gesture", "{}")))
                .thenReturn("done");

        AgentRunResult result = loop(limits).run(task(), toolbox);

        assertThat(result.outcome()).isEqualTo(AgentRunOutcome.COMPLETED);
        assertThat(result.steps()).isEqualTo(3);
        assertThat(llm.requests().getFirst().tools()).extracting(ToolSpec::name).contains("mark");
    }

    @Test
    void sendsModelReasoningToTheSessionLog() {
        llm.thenReturn(new ScriptedLlm.Reply("done", 100, "I should tap login"));

        loop(AgentLoopConfig.DEFAULT).run(task(), toolbox(AgentLoopConfig.DEFAULT));

        assertThat(logger.reasoning).singleElement().asString().contains("I should tap login");
    }

    @Test
    void registeringTheSameSyntheticToolTwiceIsRejected() {
        SyntheticTool tool = new SyntheticTool(new ToolSpec("mark", "x", "{}"), args -> ToolOutcome.text("ok"));
        AgentToolbox toolbox = toolbox(AgentLoopConfig.DEFAULT).with(tool);

        assertThatThrownBy(() -> toolbox.with(tool)).isInstanceOf(IllegalArgumentException.class);
    }

    private AgentLoop loop(AgentLoopConfig limits) {
        return new AgentLoop(llm, limits, logger);
    }

    private AgentToolbox toolbox(AgentLoopConfig limits) {
        return new AgentToolbox(mcp, limits, new XmlPageSourceCleaner(), telemetry);
    }

    private static AgentLoopConfig limits(int steps, int withoutProgress, long tokens) {
        return new AgentLoopConfig(steps, withoutProgress, tokens, Duration.ofSeconds(60), 12_000,
                AgentLoopConfig.DEFAULT_ALLOWED_TOOLS);
    }

    private static AgentTask task() {
        return new AgentTask(LlmTier.TIER_2, MigrationStage.STAGE_2_SNAPSHOTS, "navigate:test", "system", "user", false);
    }

    private static List<ToolResultMessage> toolResults(com.project.migration.domain.model.llm.LlmRequest<?> request) {
        return request.messages().stream().filter(m -> m instanceof ToolResultMessage)
                .map(m -> (ToolResultMessage) m).toList();
    }
}
