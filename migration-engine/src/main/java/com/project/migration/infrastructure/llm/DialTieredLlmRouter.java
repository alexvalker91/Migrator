package com.project.migration.infrastructure.llm;

import com.project.migration.domain.exception.LlmCallException;
import com.project.migration.domain.model.config.EngineConfig;
import com.project.migration.domain.model.llm.LlmRequest;
import com.project.migration.domain.model.llm.LlmResponse;
import com.project.migration.domain.model.llm.TierModelConfig;
import com.project.migration.domain.model.llm.ToolCall;
import com.project.migration.domain.model.telemetry.TokenUsage;
import com.project.migration.domain.port.LlmClientPort;
import com.project.migration.domain.port.MigrationLoggerPort;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiTokenUsage;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link LlmClientPort} over EPAM AI DIAL (LangChain4j {@code OpenAiChatModel} per deployment).
 * <ul>
 *   <li><b>Routing:</b> the tier's deployment chain ({@link TierModelConfig#deploymentChain(boolean)}); the
 *       reasoning deployment when {@code reasoningRequested}, the base model with a warning when the tier has none.</li>
 *   <li><b>Failover:</b> the next deployment only when the current one is unavailable (404, 5xx, timeout after
 *       LangChain4j's retries), never on bad requests or bad answers.</li>
 *   <li><b>Structured output:</b> strict {@code json_schema}; deployments that reject it are switched (for the
 *       rest of the process) to the schema in the system prompt. An unparsable answer gets one repair turn.</li>
 *   <li><b>Vision:</b> screenshots only for tiers that support it, checked before any network call.</li>
 * </ul>
 * One call = one model turn; tool calls are returned, not executed.
 */
public final class DialTieredLlmRouter implements LlmClientPort {

    private final EngineConfig config;
    private final ChatModelFactory factory;
    private final MigrationLoggerPort logger;
    private final Clock clock;
    private final Map<String, ChatModel> models = new ConcurrentHashMap<>();
    private final Set<String> withoutJsonSchema = ConcurrentHashMap.newKeySet();

    public DialTieredLlmRouter(EngineConfig config, ChatModelFactory factory, MigrationLoggerPort logger, Clock clock) {
        this.config = config;
        this.factory = factory;
        this.logger = logger;
        this.clock = clock;
    }

    @Override
    public <T> LlmResponse<T> chat(LlmRequest<T> request) {
        TierModelConfig tier = config.modelFor(request.tier());
        if (request.hasImages() && !tier.supportsVision()) {
            throw new LlmCallException("'" + request.purpose() + "' sends screenshots, but " + request.tier()
                    + " (" + tier.modelId() + ") has no vision support");
        }
        if (tier.fallsBackFromReasoning(request.reasoningRequested())) {
            logger.warn(request.tier() + " has no reasoning deployment; '" + request.purpose()
                    + "' uses " + tier.modelId() + " without reasoning");
        }
        Optional<StructuredOutput<T>> structured = request.isStructured()
                ? Optional.of(StructuredOutput.of(request.responseType()))
                : Optional.empty();

        List<String> chain = tier.deploymentChain(request.reasoningRequested());
        RuntimeException lastFailure = null;
        for (int i = 0; i < chain.size(); i++) {
            String modelId = chain.get(i);
            try {
                return callDeployment(modelId, request, structured);
            } catch (DeploymentUnavailableException e) {
                lastFailure = e;
                String next = i + 1 < chain.size() ? "; trying " + chain.get(i + 1) : "";
                logger.warn("Deployment " + modelId + " is unavailable for '" + request.purpose() + "' ("
                        + e.getCause().getMessage() + ")" + next);
            }
        }
        throw new LlmCallException("All deployments of " + request.tier() + " are unavailable " + chain
                + " for '" + request.purpose() + "'", lastFailure == null ? null : lastFailure.getCause());
    }

    private <T> LlmResponse<T> callDeployment(String modelId, LlmRequest<T> request,
                                              Optional<StructuredOutput<T>> structured) {
        Instant start = clock.instant();
        List<ChatMessage> messages = new ArrayList<>(LlmMessageMapper.toChatMessages(request.messages()));
        ChatResponse response = send(modelId, request, messages, structured);
        TokenUsage usage = usageOf(response);
        AiMessage answer = response.aiMessage();
        List<ToolCall> toolCalls = LlmMessageMapper.toToolCalls(answer);
        Optional<String> text = Optional.ofNullable(answer.text()).filter(t -> !t.isBlank());

        Optional<T> content;
        if (structured.isEmpty()) {
            content = text.map(request.responseType()::cast);
        } else if (text.isEmpty() && !toolCalls.isEmpty()) {
            content = Optional.empty();
        } else {
            try {
                content = Optional.of(structured.get().parse(text.orElse("")));
            } catch (IllegalArgumentException invalid) {
                messages.add(answer.text() == null ? AiMessage.from("") : AiMessage.from(answer.text()));
                messages.add(UserMessage.from("Your previous answer is not valid: " + invalid.getMessage()
                        + ". Reply again with only the corrected JSON object."));
                ChatResponse repaired = send(modelId, request, messages, structured);
                usage = usage.plus(usageOf(repaired));
                answer = repaired.aiMessage();
                toolCalls = LlmMessageMapper.toToolCalls(answer);
                try {
                    content = Optional.of(structured.get().parse(answer.text()));
                } catch (IllegalArgumentException stillInvalid) {
                    throw new LlmCallException("'" + request.purpose() + "': " + modelId + " returned invalid "
                            + request.responseType().getSimpleName() + " JSON twice: " + stillInvalid.getMessage());
                }
            }
        }
        if (content.isEmpty() && toolCalls.isEmpty()) {
            throw new LlmCallException("'" + request.purpose() + "': " + modelId + " returned an empty answer");
        }
        return new LlmResponse<>(content, Optional.ofNullable(answer.thinking()), toolCalls, usage, modelId,
                Duration.between(start, clock.instant()));
    }

    private <T> ChatResponse send(String modelId, LlmRequest<T> request, List<ChatMessage> messages,
                                  Optional<StructuredOutput<T>> structured) {
        boolean schemaInPrompt = structured.isPresent() && withoutJsonSchema.contains(modelId);
        ChatRequest.Builder chat = ChatRequest.builder()
                .messages(schemaInPrompt ? withInstruction(messages, structured.get().promptInstruction()) : messages);
        if (!request.tools().isEmpty()) {
            chat.toolSpecifications(ToolSpecMapper.toToolSpecifications(request.tools()));
        }
        if (structured.isPresent() && !schemaInPrompt) {
            chat.responseFormat(structured.get().responseFormat());
        }
        try {
            return models.computeIfAbsent(modelId, factory::create).chat(chat.build());
        } catch (LlmCallException e) {
            throw e;
        } catch (RuntimeException e) {
            switch (LlmFailures.classify(e)) {
                case JSON_SCHEMA_UNSUPPORTED -> {
                    if (structured.isPresent() && !schemaInPrompt) {
                        withoutJsonSchema.add(modelId);
                        logger.warn("Deployment " + modelId + " rejects response_format json_schema; "
                                + "sending the schema in the prompt from now on");
                        return send(modelId, request, messages, structured);
                    }
                    throw new LlmCallException(failure(modelId, request, e), e);
                }
                case DEPLOYMENT_UNAVAILABLE -> throw new DeploymentUnavailableException(e);
                default -> throw new LlmCallException(failure(modelId, request, e), e);
            }
        }
    }

    private static List<ChatMessage> withInstruction(List<ChatMessage> messages, String instruction) {
        List<ChatMessage> result = new ArrayList<>(messages);
        if (!result.isEmpty() && result.getFirst() instanceof SystemMessage system) {
            result.set(0, SystemMessage.from(system.text() + "\n\n" + instruction));
        } else {
            result.addFirst(SystemMessage.from(instruction));
        }
        return result;
    }

    /** Domain usage: reasoning tokens are reported separately from output (OpenAI counts them inside it). */
    static TokenUsage usageOf(ChatResponse response) {
        dev.langchain4j.model.output.TokenUsage usage = response.tokenUsage();
        if (usage == null) {
            return TokenUsage.ZERO;
        }
        long input = orZero(usage.inputTokenCount());
        long output = orZero(usage.outputTokenCount());
        long reasoning = 0;
        long cached = 0;
        if (usage instanceof OpenAiTokenUsage openAi) {
            if (openAi.outputTokensDetails() != null) {
                reasoning = orZero(openAi.outputTokensDetails().reasoningTokens());
            }
            if (openAi.inputTokensDetails() != null) {
                cached = orZero(openAi.inputTokensDetails().cachedTokens());
            }
        }
        return new TokenUsage(input, Math.max(0, output - reasoning), reasoning, Math.min(cached, input));
    }

    private static long orZero(Integer value) {
        return value == null ? 0 : value;
    }

    private static String failure(String modelId, LlmRequest<?> request, RuntimeException e) {
        return "LLM call '" + request.purpose() + "' to " + modelId + " failed: " + e.getMessage();
    }

    /** Internal signal for failover; never leaves the router. */
    private static final class DeploymentUnavailableException extends RuntimeException {
        DeploymentUnavailableException(RuntimeException cause) {
            super(cause);
        }
    }
}
