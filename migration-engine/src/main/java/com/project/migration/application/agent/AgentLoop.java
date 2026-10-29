package com.project.migration.application.agent;

import com.project.migration.domain.model.Checks;
import com.project.migration.domain.model.agent.AgentRunOutcome;
import com.project.migration.domain.model.config.AgentLoopConfig;
import com.project.migration.domain.model.llm.AssistantMessage;
import com.project.migration.domain.model.llm.LlmMessage;
import com.project.migration.domain.model.llm.LlmRequest;
import com.project.migration.domain.model.llm.LlmResponse;
import com.project.migration.domain.model.llm.SystemMessage;
import com.project.migration.domain.model.llm.ToolCall;
import com.project.migration.domain.model.llm.ToolResultMessage;
import com.project.migration.domain.model.llm.UserMessage;
import com.project.migration.domain.port.LlmClientPort;
import com.project.migration.domain.port.MigrationLoggerPort;

import java.util.ArrayList;
import java.util.List;

/**
 * Tool-calling loop over {@link LlmClientPort}: the model asks for tools, the {@link AgentToolbox} runs them,
 * the results go back, until the model answers without tool calls or a limit is hit. Limits end the run with an
 * {@link AgentRunOutcome}; they are not errors. Only the latest screen XMLs stay in the conversation.
 */
public final class AgentLoop {

    static final String OMITTED_SCREEN = "[older screen XML omitted; call the page source tool again if needed]";
    private static final int KEPT_SCREENS = 2;
    private static final int TRACE_HEAD_CHARS = 160;

    private final LlmClientPort llm;
    private final AgentLoopConfig config;
    private final MigrationLoggerPort logger;

    public AgentLoop(LlmClientPort llm, AgentLoopConfig config, MigrationLoggerPort logger) {
        this.llm = Checks.notNull(llm, "llm");
        this.config = Checks.notNull(config, "config");
        this.logger = Checks.notNull(logger, "logger");
    }

    /**
     * @throws com.project.migration.domain.exception.LlmCallException if the gateway fails
     * @throws com.project.migration.domain.exception.McpException      if an MCP session fails
     */
    public AgentRunResult run(AgentTask task, AgentToolbox toolbox) {
        List<LlmMessage> messages = new ArrayList<>();
        messages.add(new SystemMessage(task.systemPrompt()));
        messages.add(new UserMessage(task.userPrompt(), task.images()));
        List<ScreenMarker> screens = new ArrayList<>();
        List<ToolTrace> trace = new ArrayList<>();
        var tools = toolbox.specs();

        long tokens = 0;
        int steps = 0;
        int stepsWithoutProgress = 0;
        while (true) {
            LlmRequest<String> request = new LlmRequest<>(task.tier(), task.stage(), task.purpose(),
                    List.copyOf(messages), tools, String.class, task.reasoning());
            LlmResponse<String> response = llm.chat(request);
            tokens += response.usage().total();
            if (!response.hasToolCalls()) {
                return new AgentRunResult(AgentRunOutcome.COMPLETED, steps, tokens,
                        response.content().orElse(""), trace);
            }

            steps++;
            messages.add(new AssistantMessage(response.content(), response.toolCalls()));
            boolean progressed = false;
            for (ToolCall call : response.toolCalls()) {
                ToolOutcome outcome = toolbox.execute(call);
                progressed |= outcome.progress();
                messages.add(new ToolResultMessage(call.id(), call.name(), outcome.content()));
                if (outcome.pageSource()) {
                    screens.add(new ScreenMarker(messages.size() - 1, call));
                }
                trace.add(new ToolTrace(call.name(), call.argumentsJson(), head(outcome.content()), outcome.isError()));
                logger.info("agent " + task.purpose() + " step " + steps + ": " + call.name()
                        + (outcome.isError() ? " -> error: " + head(outcome.content()) : ""));
            }
            dropOldScreens(messages, screens);
            stepsWithoutProgress = progressed ? 0 : stepsWithoutProgress + 1;

            if (steps >= config.maxSteps() || tokens >= config.maxTokens()) {
                return new AgentRunResult(AgentRunOutcome.BUDGET_EXHAUSTED, steps, tokens, "", trace);
            }
            if (stepsWithoutProgress >= config.maxStepsWithoutProgress()) {
                return new AgentRunResult(AgentRunOutcome.STUCK, steps, tokens, "", trace);
            }
        }
    }

    private static void dropOldScreens(List<LlmMessage> messages, List<ScreenMarker> screens) {
        while (screens.size() > KEPT_SCREENS) {
            ScreenMarker old = screens.removeFirst();
            messages.set(old.index(), new ToolResultMessage(old.call().id(), old.call().name(), OMITTED_SCREEN));
        }
    }

    private static String head(String text) {
        String oneLine = text.replaceAll("\\s+", " ").strip();
        return oneLine.length() <= TRACE_HEAD_CHARS ? oneLine : oneLine.substring(0, TRACE_HEAD_CHARS - 3) + "...";
    }

    private record ScreenMarker(int index, ToolCall call) {
    }
}
