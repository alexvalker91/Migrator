package com.project.migration.application.snapshot;

import com.project.migration.application.agent.AgentLoop;
import com.project.migration.application.agent.AgentRunResult;
import com.project.migration.application.agent.AgentTask;
import com.project.migration.application.agent.AgentToolbox;
import com.project.migration.application.agent.ToolTrace;
import com.project.migration.application.pagesource.XmlPageSourceCleaner;
import com.project.migration.application.prompt.PromptTemplates;
import com.project.migration.application.telemetry.TelemetryCollector;
import com.project.migration.domain.exception.McpException;
import com.project.migration.domain.model.Checks;
import com.project.migration.domain.model.MigrationStage;
import com.project.migration.domain.model.Platform;
import com.project.migration.domain.model.agent.AgentRunOutcome;
import com.project.migration.domain.model.agent.AndroidWalkthrough;
import com.project.migration.domain.model.agent.StateObservation;
import com.project.migration.domain.model.agent.WalkthroughAction;
import com.project.migration.domain.model.agent.WalkthroughStep;
import com.project.migration.domain.model.analysis.MigrationTaskDelta;
import com.project.migration.domain.model.analysis.TestMethodTask;
import com.project.migration.domain.model.code.MethodRef;
import com.project.migration.domain.model.code.TestMethodRef;
import com.project.migration.domain.model.config.AgentLoopConfig;
import com.project.migration.domain.model.llm.LlmRequest;
import com.project.migration.domain.model.llm.LlmTier;
import com.project.migration.domain.model.llm.SystemMessage;
import com.project.migration.domain.model.llm.UserMessage;
import com.project.migration.domain.model.screen.ScreenId;
import com.project.migration.domain.model.screen.ScreenState;
import com.project.migration.domain.model.screen.ScreenStateKey;
import com.project.migration.domain.model.snapshot.AgentRunSummary;
import com.project.migration.domain.model.snapshot.SnapshotCollectionResult;
import com.project.migration.domain.model.snapshot.UnreachableReason;
import com.project.migration.domain.model.snapshot.UnreachableState;
import com.project.migration.domain.port.CodeAnalyzerPort;
import com.project.migration.domain.port.LlmClientPort;
import com.project.migration.domain.port.McpClientPort;
import com.project.migration.domain.port.McpClientsPort;
import com.project.migration.domain.port.MigrationLoggerPort;
import com.project.migration.domain.port.SnapshotRepositoryPort;
import com.project.migration.domain.port.WalkthroughSinkPort;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Stage 2: brings both apps to the missing screen states and stores a snapshot pair for each. One pass per
 * test, not per state: a clean app restart, then the Android reference pass captures every missing state the
 * test reaches, then the iOS pass repeats the recorded {@link AndroidWalkthrough} and captures the same
 * states. States a pass does not reach stay missing and are retried by later tests of the class; whatever is
 * still missing at the end is reported, never thrown.
 */
public final class SnapshotCollectionService implements SnapshotCollector {

    private static final int SOURCE_LIMIT = 6_000;

    private final McpClientsPort mcps;
    private final SnapshotRepositoryPort snapshots;
    private final LlmClientPort llm;
    private final CodeAnalyzerPort analyzer;
    private final WalkthroughSinkPort walkthroughs;
    private final XmlPageSourceCleaner cleaner;
    private final TelemetryCollector telemetry;
    private final MigrationLoggerPort logger;
    private final AgentLoopConfig loopConfig;
    private final Clock clock;
    private final AgentLoop loop;

    public SnapshotCollectionService(McpClientsPort mcps, SnapshotRepositoryPort snapshots, LlmClientPort llm,
                                     CodeAnalyzerPort analyzer, WalkthroughSinkPort walkthroughs,
                                     XmlPageSourceCleaner cleaner, TelemetryCollector telemetry,
                                     MigrationLoggerPort logger, AgentLoopConfig loopConfig, Clock clock) {
        this.mcps = Checks.notNull(mcps, "mcps");
        this.snapshots = Checks.notNull(snapshots, "snapshots");
        this.llm = Checks.notNull(llm, "llm");
        this.analyzer = Checks.notNull(analyzer, "analyzer");
        this.walkthroughs = Checks.notNull(walkthroughs, "walkthroughs");
        this.cleaner = Checks.notNull(cleaner, "cleaner");
        this.telemetry = Checks.notNull(telemetry, "telemetry");
        this.logger = Checks.notNull(logger, "logger");
        this.loopConfig = Checks.notNull(loopConfig, "loopConfig");
        this.clock = Checks.notNull(clock, "clock");
        this.loop = new AgentLoop(llm, loopConfig, logger);
    }

    /**
     * @throws com.project.migration.domain.exception.LlmCallException if the gateway fails
     */
    @Override
    public SnapshotCollectionResult collect(MigrationTaskDelta delta) {
        if (delta.missingSnapshots().isEmpty()) {
            logger.info("No snapshots to collect for " + delta.testClassFqcn());
            return SnapshotCollectionResult.nothingToDo();
        }
        Set<ScreenStateKey> pending = new LinkedHashSet<>(delta.missingSnapshots());
        Set<ScreenStateKey> captured = new LinkedHashSet<>();
        Map<ScreenStateKey, UnreachableState> unreachable = new LinkedHashMap<>();
        List<AgentRunSummary> runs = new ArrayList<>();

        for (TestMethodTask task : delta.tasks()) {
            Set<ScreenStateKey> wanted = task.requiredStates().stream().filter(pending::contains)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            if (wanted.isEmpty()) {
                continue;
            }
            logger.info("Collecting " + wanted + " through " + task.test().methodName());
            try {
                collectForTest(delta, task, wanted, captured, unreachable, runs);
            } catch (McpException e) {
                logger.warn("MCP failure while collecting for " + task.test().methodName() + ": " + e.getMessage());
                wanted.stream().filter(key -> !captured.contains(key)).forEach(key -> unreachable.put(key,
                        new UnreachableState(key, e.platform(), UnreachableReason.MCP_ERROR, e.getMessage())));
            }
            pending.removeAll(captured);
        }
        pending.forEach(key -> unreachable.computeIfAbsent(key, k -> new UnreachableState(k, Platform.ANDROID,
                UnreachableReason.NOT_REACHED, "no test of the class reached this state")));
        captured.forEach(unreachable::remove);
        return new SnapshotCollectionResult(captured, List.copyOf(unreachable.values()), runs);
    }

    private void collectForTest(MigrationTaskDelta delta, TestMethodTask task, Set<ScreenStateKey> wanted,
                                Set<ScreenStateKey> captured, Map<ScreenStateKey, UnreachableState> unreachable,
                                List<AgentRunSummary> runs) {
        Pass android = runPass(delta, task, Platform.ANDROID, wanted, Optional.empty());
        runs.add(android.summary(task.test()));
        if (android.captured().isEmpty()) {
            wanted.forEach(key -> unreachable.put(key, notReached(key, Platform.ANDROID, android.result(), "")));
            return;
        }
        AndroidWalkthrough walkthrough = summarize(task.test(), android);
        walkthroughs.save(delta.testClassFqcn(), walkthrough);

        Pass ios = runPass(delta, task, Platform.IOS, android.captured(), Optional.of(walkthrough));
        runs.add(ios.summary(task.test()));
        for (ScreenStateKey key : wanted) {
            if (!android.captured().contains(key)) {
                unreachable.put(key, notReached(key, Platform.ANDROID, android.result(), ""));
            } else if (!ios.captured().contains(key)) {
                unreachable.put(key, notReached(key, Platform.IOS, ios.result(), ""));
            } else {
                captured.add(key);
                unreachable.remove(key);
            }
        }
    }

    private Pass runPass(MigrationTaskDelta delta, TestMethodTask task, Platform platform, Set<ScreenStateKey> wanted,
                         Optional<AndroidWalkthrough> walkthrough) {
        McpClientPort mcp = mcps.forPlatform(platform);
        mcp.restartApp();
        SnapshotCaptureTool capture = new SnapshotCaptureTool(mcp, cleaner, snapshots, telemetry, logger, clock, wanted);
        AgentToolbox toolbox = new AgentToolbox(mcp, loopConfig, cleaner, telemetry).with(capture.tool());
        AgentTask agentTask = new AgentTask(LlmTier.TIER_2, MigrationStage.STAGE_2_SNAPSHOTS,
                "navigate-" + platform.fileToken() + ":" + task.test().key(), systemPrompt(platform, walkthrough),
                userPrompt(delta, task, wanted, walkthrough), false);
        AgentRunResult result = loop.run(agentTask, toolbox);
        logger.info(platform + " pass of " + task.test().methodName() + " ended " + result.outcome() + " after "
                + result.steps() + " steps, captured " + capture.captured());
        return new Pass(platform, result, capture.captured());
    }

    private AndroidWalkthrough summarize(TestMethodRef test, Pass android) {
        StringBuilder log = new StringBuilder();
        int n = 1;
        for (ToolTrace trace : android.result().trace()) {
            log.append(n++).append(". ").append(trace.tool()).append(' ').append(trace.argumentsJson())
                    .append(" -> ").append(trace.error() ? "ERROR " : "").append(trace.resultHead()).append('\n');
        }
        String user = PromptTemplates.render("walkthrough-summary.user", Map.of(
                "test", test.key(),
                "captured", android.captured().toString(),
                "log", log.isEmpty() ? "(no tool calls)" : log.toString().strip(),
                "finalText", android.result().finalText().isBlank() ? "(none)" : android.result().finalText()));
        WalkthroughSummary summary = llm.chat(LlmRequest.structured(LlmTier.TIER_2, MigrationStage.STAGE_2_SNAPSHOTS,
                "walkthrough-summary:" + test.key(),
                List.of(new SystemMessage(PromptTemplates.render("walkthrough-summary.system", Map.of())),
                        UserMessage.of(user)), WalkthroughSummary.class)).requireContent();
        return toWalkthrough(test, summary, android.captured());
    }

    private static AndroidWalkthrough toWalkthrough(TestMethodRef test, WalkthroughSummary summary,
                                                    Set<ScreenStateKey> captured) {
        List<WalkthroughStep> steps = new ArrayList<>();
        for (WalkthroughSummary.Action action : orEmpty(summary.steps())) {
            steps.add(new WalkthroughStep(actionOf(action.action()), action.target() == null ? "" : action.target(),
                    Optional.ofNullable(action.value())));
        }
        List<StateObservation> observations = new ArrayList<>();
        for (WalkthroughSummary.Observation observation : orEmpty(summary.observations())) {
            try {
                ScreenStateKey key = new ScreenStateKey(new ScreenId(observation.screen().strip()),
                        ScreenState.valueOf(observation.state().strip().toUpperCase(Locale.ROOT)),
                        observation.variant() == null || observation.variant().isBlank()
                                ? ScreenStateKey.DEFAULT_VARIANT : observation.variant().strip());
                if (captured.contains(key)) {
                    observations.add(new StateObservation(key, orEmpty(observation.visibleTexts())));
                }
            } catch (IllegalArgumentException | NullPointerException ignored) {
                // an observation of a state that was not captured, or a malformed one, is of no use to the iOS pass
            }
        }
        return new AndroidWalkthrough(test, steps, observations);
    }

    private static WalkthroughAction actionOf(String text) {
        try {
            return WalkthroughAction.valueOf(text.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            return WalkthroughAction.OTHER;
        }
    }

    private static String systemPrompt(Platform platform, Optional<AndroidWalkthrough> walkthrough) {
        String role = walkthrough.isPresent()
                ? "An Android pass has already reached these states; its recorded walkthrough is below. Repeat its "
                + "actions on iOS. Labels and structure may differ: recognise elements by their meaning, using the "
                + "texts the Android pass saw."
                : "You are the reference pass: the real Android app shows the real texts and structure. In your "
                + "final answer list, for each captured state, the main visible texts (titles, labels, buttons).";
        return PromptTemplates.render("snapshot-navigation.system", Map.of("platform", platform.runnerValue(), "role", role));
    }

    private String userPrompt(MigrationTaskDelta delta, TestMethodTask task, Set<ScreenStateKey> wanted,
                              Optional<AndroidWalkthrough> walkthrough) {
        String setup = String.join("\n\n", analyzer.setupMethodSources(delta.testClassFqcn()));
        return PromptTemplates.render("snapshot-navigation.user", Map.of(
                "test", task.test().methodName(),
                "testSource", cut(analyzer.testSource(task.test()).orElse("// source unavailable")),
                "setup", setup.isBlank() ? "// none" : cut(setup),
                "steps", task.callTree().stream().map(MethodRef::key).collect(Collectors.joining(", ")),
                "wanted", wanted.stream().map(SnapshotCollectionService::describe).collect(Collectors.joining("\n")),
                "walkthrough", walkthrough.map(SnapshotCollectionService::describe).orElse("")));
    }

    private static String describe(ScreenStateKey key) {
        return "- screen=" + key.screen() + ", state=" + key.state() + ", variant=" + key.variant();
    }

    private static String describe(AndroidWalkthrough walkthrough) {
        StringBuilder text = new StringBuilder("\nRecorded Android walkthrough:\n");
        int n = 1;
        for (WalkthroughStep step : walkthrough.steps()) {
            text.append(n++).append(". ").append(step.action()).append(" '").append(step.target()).append('\'')
                    .append(step.value().map(value -> " value '" + value + "'").orElse("")).append('\n');
        }
        for (StateObservation observation : walkthrough.observations()) {
            text.append("Android showed in ").append(observation.key()).append(": ")
                    .append(observation.visibleTexts()).append('\n');
        }
        return text.toString();
    }

    private static UnreachableState notReached(ScreenStateKey key, Platform platform, AgentRunResult result, String detail) {
        UnreachableReason reason = switch (result.outcome()) {
            case COMPLETED -> UnreachableReason.NOT_REACHED;
            case BUDGET_EXHAUSTED -> UnreachableReason.BUDGET_EXHAUSTED;
            case STUCK -> UnreachableReason.STUCK;
        };
        String text = detail.isBlank() ? result.finalText() : detail;
        return new UnreachableState(key, platform, reason, text == null ? "" : text);
    }

    private static String cut(String text) {
        return text.length() <= SOURCE_LIMIT ? text : text.substring(0, SOURCE_LIMIT) + "\n// ... cut";
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    private record Pass(Platform platform, AgentRunResult result, Set<ScreenStateKey> captured) {
        AgentRunSummary summary(TestMethodRef test) {
            AgentRunOutcome outcome = result.outcome();
            return new AgentRunSummary(test, platform, outcome, result.steps(), result.tokens());
        }
    }
}
