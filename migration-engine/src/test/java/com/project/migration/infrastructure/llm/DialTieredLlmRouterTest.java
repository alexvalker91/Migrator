package com.project.migration.infrastructure.llm;

import com.project.migration.domain.exception.LlmCallException;
import com.project.migration.domain.model.MigrationStage;
import com.project.migration.domain.model.code.TestMethodRef;
import com.project.migration.domain.model.config.EngineConfig;
import com.project.migration.domain.model.llm.AssistantMessage;
import com.project.migration.domain.model.llm.LlmRequest;
import com.project.migration.domain.model.llm.LlmResponse;
import com.project.migration.domain.model.llm.LlmTier;
import com.project.migration.domain.model.llm.SystemMessage;
import com.project.migration.domain.model.llm.TierModelConfig;
import com.project.migration.domain.model.llm.ToolCall;
import com.project.migration.domain.model.llm.ToolResultMessage;
import com.project.migration.domain.model.llm.ToolSpec;
import com.project.migration.domain.model.llm.UserMessage;
import com.project.migration.domain.model.media.ImageAttachment;
import com.project.migration.domain.model.telemetry.TokenUsage;
import com.project.migration.domain.port.MigrationLoggerPort;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.InternalServerException;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.exception.ModelNotFoundException;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonRawSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiTokenUsage;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DialTieredLlmRouterTest {

    /** Structured answer type used by the tests. */
    record LocatorAnswer(String strategy, String value, List<String> alternatives, Optional<String> note) {
    }

    private static final EngineConfig CONFIG = EngineConfig.withDefaults(Path.of("/work"), false, List.of(
            new TierModelConfig(LlmTier.TIER_1, "opus", Optional.of("astra-reasoning"), true,
                    List.of("sol"), List.of("sol-reasoning")),
            new TierModelConfig(LlmTier.TIER_2, "sonnet", Optional.empty(), true, List.of("codex"), List.of()),
            new TierModelConfig(LlmTier.TIER_3, "haiku", Optional.empty(), false)), List.of());

    private final FakeModels models = new FakeModels();
    private final RecordingLogger logger = new RecordingLogger();
    private final DialTieredLlmRouter router = new DialTieredLlmRouter(CONFIG, models, logger,
            Clock.fixed(Instant.parse("2026-10-29T10:00:00Z"), ZoneOffset.UTC));

    @Test
    void textRequestUsesTheTierBaseDeploymentAndMapsUsage() {
        models.answer("sonnet", request -> response("Hello", null, usage(1000, 300, 120, 400)));

        LlmResponse<String> response = router.chat(text(LlmTier.TIER_2));

        assertThat(response.requireContent()).isEqualTo("Hello");
        assertThat(response.modelId()).isEqualTo("sonnet");
        assertThat(response.usage()).isEqualTo(new TokenUsage(1000, 180, 120, 400));
        assertThat(response.reasoning()).isEmpty();
        assertThat(models.created).containsExactly("sonnet");
    }

    @Test
    void reasoningRequestUsesTheReasoningDeploymentAndReturnsThinking() {
        models.answer("astra-reasoning", request -> response("Fix: scroll first", "spinner still visible", usage(10, 5, 0, 0)));

        LlmResponse<String> response = router.chat(text(LlmTier.TIER_1).withReasoning(true));

        assertThat(response.modelId()).isEqualTo("astra-reasoning");
        assertThat(response.reasoning()).contains("spinner still visible");
    }

    @Test
    void reasoningOnATierWithoutReasoningDeploymentFallsBackWithAWarning() {
        models.answer("sonnet", request -> response("ok", null, usage(1, 1, 0, 0)));

        assertThat(router.chat(text(LlmTier.TIER_2).withReasoning(true)).modelId()).isEqualTo("sonnet");
        assertThat(logger.warnings).singleElement().asString().contains("no reasoning deployment");
    }

    @Test
    void unavailableDeploymentFailsOverToTheNextOneOfTheTier() {
        models.answer("astra-reasoning", request -> {
            throw new ModelNotFoundException("deployment not found");
        });
        models.answer("sol-reasoning", request -> {
            throw new InternalServerException(new HttpException(503, "overloaded"));
        });
        models.answer("opus", request -> response("answer", null, usage(1, 1, 0, 0)));

        LlmResponse<String> response = router.chat(text(LlmTier.TIER_1).withReasoning(true));

        assertThat(response.modelId()).isEqualTo("opus");
        assertThat(models.created).containsExactly("astra-reasoning", "sol-reasoning", "opus");
        assertThat(logger.warnings).hasSize(2).first().asString()
                .contains("astra-reasoning is unavailable", "trying sol-reasoning");
    }

    @Test
    void badRequestsAndAuthenticationFailuresDoNotFailOver() {
        models.answer("sonnet", request -> {
            throw new InvalidRequestException(new HttpException(400, "messages: too long"));
        });

        assertThatThrownBy(() -> router.chat(text(LlmTier.TIER_2)))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("sonnet failed");
        assertThat(models.created).containsExactly("sonnet");

        models.answer("haiku", request -> {
            throw new AuthenticationException("401");
        });
        assertThatThrownBy(() -> router.chat(text(LlmTier.TIER_3))).isInstanceOf(LlmCallException.class);
    }

    @Test
    void exhaustedChainNamesEveryDeployment() {
        models.answer("sonnet", request -> {
            throw new InternalServerException("500");
        });
        models.answer("codex", request -> {
            throw new ModelNotFoundException("404");
        });

        assertThatThrownBy(() -> router.chat(text(LlmTier.TIER_2)))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("[sonnet, codex]");
    }

    @Test
    void structuredRequestSendsStrictJsonSchemaAndParsesTheRecord() {
        models.answer("sonnet", request -> response(
                "{\"strategy\":\"accessibility\",\"value\":\"login\",\"alternatives\":[\"name == 'login'\"]}",
                null, usage(1, 1, 0, 0)));

        LocatorAnswer answer = router.chat(structured(LlmTier.TIER_2)).requireContent();

        assertThat(answer).isEqualTo(new LocatorAnswer("accessibility", "login", List.of("name == 'login'"), Optional.empty()));
        ChatRequest sent = models.requests.getFirst();
        assertThat(sent.responseFormat().type()).isEqualTo(ResponseFormatType.JSON);
        assertThat(sent.responseFormat().jsonSchema().rootElement()).isNotNull();
    }

    @Test
    void deploymentWithoutJsonSchemaSupportGetsTheSchemaInThePromptFromThenOn() {
        models.answer("sonnet", request -> {
            if (request.responseFormat() != null) {
                throw new InvalidRequestException(new HttpException(400, "response_format json_schema is not supported"));
            }
            return response("```json\n{\"strategy\":\"accessibility\",\"value\":\"x\",\"alternatives\":[]}\n```",
                    null, usage(1, 1, 0, 0));
        });

        router.chat(structured(LlmTier.TIER_2));
        router.chat(structured(LlmTier.TIER_2));

        assertThat(models.requests).hasSize(3);
        ChatRequest promptMode = models.requests.get(1);
        assertThat(promptMode.responseFormat()).isNull();
        assertThat(((dev.langchain4j.data.message.SystemMessage) promptMode.messages().getFirst()).text())
                .startsWith("You map locators.")
                .contains("JSON Schema", "\"strategy\"");
        assertThat(models.requests.get(2).responseFormat()).isNull();
        assertThat(logger.warnings).singleElement().asString().contains("rejects response_format");
    }

    @Test
    void invalidJsonGetsOneRepairTurnAndUsageAddsUp() {
        Deque<String> answers = new ArrayDeque<>(List.of("not json", "{\"strategy\":\"a\",\"value\":\"b\",\"alternatives\":[]}"));
        models.answer("sonnet", request -> response(answers.removeFirst(), null, usage(100, 10, 0, 0)));

        LlmResponse<LocatorAnswer> response = router.chat(structured(LlmTier.TIER_2));

        assertThat(response.requireContent().value()).isEqualTo("b");
        assertThat(response.usage()).isEqualTo(new TokenUsage(200, 20, 0, 0));
        List<ChatMessage> repairTurn = models.requests.get(1).messages();
        assertThat(repairTurn.get(repairTurn.size() - 2)).isInstanceOf(AiMessage.class);
        assertThat(((dev.langchain4j.data.message.UserMessage) repairTurn.getLast()).singleText())
                .contains("not valid");
    }

    @Test
    void secondInvalidAnswerFails() {
        models.answer("sonnet", request -> response("still not json", null, usage(1, 1, 0, 0)));

        assertThatThrownBy(() -> router.chat(structured(LlmTier.TIER_2)))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("invalid LocatorAnswer JSON twice");
        assertThat(models.requests).hasSize(2);
    }

    @Test
    void screenshotsToATierWithoutVisionFailBeforeAnyCall() {
        LlmRequest<String> request = LlmRequest.text(LlmTier.TIER_3, MigrationStage.STAGE_4_STABILIZATION, "heal",
                List.of(UserMessage.withImages("What is wrong?", ImageAttachment.png(new byte[]{1, 2}))));

        assertThatThrownBy(() -> router.chat(request))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("no vision support");
        assertThat(models.created).isEmpty();
    }

    @Test
    void conversationToolsAndImagesAreMappedAndToolCallsReturned() {
        models.answer("opus", request -> ChatResponse.builder()
                .aiMessage(AiMessage.from(List.of(ToolExecutionRequest.builder().id("call-7").name("appium_find_element")
                        .arguments("{\"strategy\":\"id\"}").build())))
                .tokenUsage(usage(5, 5, 0, 0))
                .build());
        String schema = """
                {"type":"object","properties":{"strategy":{"anyOf":[{"enum":["id","xpath"]},{"type":"null"}]}},
                 "required":["strategy"],"additionalProperties":false}""";
        LlmRequest<String> request = LlmRequest.text(LlmTier.TIER_1, MigrationStage.STAGE_2_SNAPSHOTS, "navigate",
                        List.of(new SystemMessage("Drive the app."),
                                UserMessage.withImages("Open cards", ImageAttachment.png(new byte[]{9, 8, 7})),
                                AssistantMessage.ofToolCalls(List.of(new ToolCall("call-6", "appium_screenshot", "{}"))),
                                new ToolResultMessage("call-6", "appium_screenshot", "done")))
                .withTools(List.of(new ToolSpec("appium_find_element", "Find an element", schema)));

        LlmResponse<String> response = router.chat(request);

        assertThat(response.content()).isEmpty();
        assertThat(response.toolCalls()).containsExactly(new ToolCall("call-7", "appium_find_element", "{\"strategy\":\"id\"}"));
        ChatRequest sent = models.requests.getFirst();
        assertThat(sent.messages()).hasSize(4);
        dev.langchain4j.data.message.UserMessage user = (dev.langchain4j.data.message.UserMessage) sent.messages().get(1);
        ImageContent image = (ImageContent) user.contents().get(1);
        assertThat(image.image().base64Data()).isEqualTo("CQgH");
        assertThat(image.detailLevel()).isEqualTo(ImageContent.DetailLevel.HIGH);
        assertThat(((AiMessage) sent.messages().get(2)).toolExecutionRequests().getFirst().id()).isEqualTo("call-6");
        assertThat(((ToolExecutionResultMessage) sent.messages().get(3)).text()).isEqualTo("done");
        var parameters = sent.toolSpecifications().getFirst().parameters();
        assertThat(parameters.required()).containsExactly("strategy");
        assertThat(parameters.properties().get("strategy")).isInstanceOf(JsonRawSchema.class);
        assertThat(((JsonRawSchema) parameters.properties().get("strategy")).schema()).contains("anyOf");
    }

    @Test
    void unsupportedRootSchemaKeywordsAreRejected() {
        LlmRequest<String> request = text(LlmTier.TIER_2)
                .withTools(List.of(new ToolSpec("weird", "", "{\"oneOf\":[{\"type\":\"object\"}]}")));

        assertThatThrownBy(() -> router.chat(request))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("oneOf");
    }

    private static LlmRequest<String> text(LlmTier tier) {
        return LlmRequest.text(tier, MigrationStage.STAGE_1_DELTA_ANALYSIS, "delta:LoginTest",
                List.of(UserMessage.of("Analyse")));
    }

    private static LlmRequest<LocatorAnswer> structured(LlmTier tier) {
        return LlmRequest.structured(tier, MigrationStage.STAGE_3_CODEGEN, "locators:LoginPage",
                List.of(new SystemMessage("You map locators."), UserMessage.of("Map emailField")), LocatorAnswer.class);
    }

    private static ChatResponse response(String text, String thinking, OpenAiTokenUsage usage) {
        return ChatResponse.builder()
                .aiMessage(AiMessage.builder().text(text).thinking(thinking).build())
                .tokenUsage(usage)
                .build();
    }

    private static OpenAiTokenUsage usage(int input, int output, int reasoning, int cached) {
        return OpenAiTokenUsage.builder()
                .inputTokenCount(input)
                .outputTokenCount(output)
                .totalTokenCount(input + output)
                .outputTokensDetails(OpenAiTokenUsage.OutputTokensDetails.builder().reasoningTokens(reasoning).build())
                .inputTokensDetails(OpenAiTokenUsage.InputTokensDetails.builder().cachedTokens(cached).build())
                .build();
    }

    /** Scripted deployments; unknown deployments are "not found". */
    static final class FakeModels implements ChatModelFactory {
        final Map<String, Function<ChatRequest, ChatResponse>> behaviour = new HashMap<>();
        final List<String> created = new ArrayList<>();
        final List<ChatRequest> requests = new ArrayList<>();

        void answer(String modelId, Function<ChatRequest, ChatResponse> handler) {
            behaviour.put(modelId, handler);
        }

        @Override
        public ChatModel create(String modelId) {
            created.add(modelId);
            return new ChatModel() {
                @Override
                public ChatResponse chat(ChatRequest request) {
                    requests.add(request);
                    Function<ChatRequest, ChatResponse> handler = behaviour.get(modelId);
                    if (handler == null) {
                        throw new ModelNotFoundException("no deployment " + modelId);
                    }
                    return handler.apply(request);
                }
            };
        }
    }

    static final class RecordingLogger implements MigrationLoggerPort {
        final List<String> warnings = new ArrayList<>();

        @Override
        public void startSession(String testClassFqcn) {
        }

        @Override
        public void enterStage(MigrationStage stage) {
        }

        @Override
        public void enterTest(TestMethodRef test) {
        }

        @Override
        public void exitTest() {
        }

        @Override
        public void info(String message) {
        }

        @Override
        public void warn(String message) {
            warnings.add(message);
        }

        @Override
        public void error(String message, Throwable cause) {
        }

        @Override
        public void reasoning(LlmTier tier, String purpose, String reasoningText) {
        }

        @Override
        public void endSession() {
        }
    }
}
