package com.project.migration.infrastructure.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.project.migration.domain.model.MigrationStage;
import com.project.migration.domain.model.config.EngineConfig;
import com.project.migration.domain.model.llm.LlmRequest;
import com.project.migration.domain.model.llm.LlmResponse;
import com.project.migration.domain.model.llm.LlmTier;
import com.project.migration.domain.model.llm.TierModelConfig;
import com.project.migration.domain.model.llm.ToolSpec;
import com.project.migration.domain.model.llm.UserMessage;
import com.project.migration.domain.model.media.ImageAttachment;
import com.project.migration.domain.model.telemetry.TokenUsage;
import com.project.migration.infrastructure.json.JsonMappers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real {@code OpenAiChatModel} (via {@link DialChatModelFactory}) against a local HTTP stub: verifies the DIAL
 * wire contract without network access or a key.
 */
class DialWireContractTest {

    record LocatorAnswer(String strategy, String value) {
    }

    record Exchange(String path, String apiKey, JsonNode body) {
    }

    private static final String ANSWER = """
            {"id":"chatcmpl-1","object":"chat.completion","created":1,"model":"%s",
             "choices":[{"index":0,"message":{"role":"assistant","content":%s},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":120,"completion_tokens":30,"total_tokens":150,
                      "prompt_tokens_details":{"cached_tokens":20},"completion_tokens_details":{"reasoning_tokens":10}}}
            """;

    private final List<Exchange> exchanges = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private DialTieredLlmRouter router;

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/openai/deployments/", this::handle);
        server.start();
        EngineConfig config = EngineConfig.withDefaults(Path.of("/work"), false, List.of(
                new TierModelConfig(LlmTier.TIER_1, "claude-opus-5-5", Optional.empty(), true),
                new TierModelConfig(LlmTier.TIER_2, "gone-deployment", Optional.empty(), true,
                        List.of("claude-sonnet-5-5"), List.of()),
                new TierModelConfig(LlmTier.TIER_3, "no-schema-model", Optional.empty(), true)), List.of());
        DialSettings settings = new DialSettings("http://127.0.0.1:" + server.getAddress().getPort(), "test-key-123456");
        router = new DialTieredLlmRouter(config, new DialChatModelFactory(settings),
                new DialTieredLlmRouterTest.RecordingLogger(), Clock.systemUTC());
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    @Test
    void requestFollowsTheDialDeploymentContract() {
        LlmRequest<String> request = LlmRequest.text(LlmTier.TIER_1, MigrationStage.STAGE_4_STABILIZATION, "heal",
                        List.of(UserMessage.withImages("Why did it fail?", ImageAttachment.png(new byte[]{1, 2, 3}))))
                .withTools(List.of(new ToolSpec("appium_find_element", "Find",
                        "{\"type\":\"object\",\"properties\":{\"strategy\":{\"enum\":[\"id\",\"xpath\"]}},\"required\":[\"strategy\"]}")));

        LlmResponse<String> response = router.chat(request);

        Exchange exchange = exchanges.getFirst();
        assertThat(exchange.path()).isEqualTo("/openai/deployments/claude-opus-5-5/chat/completions");
        assertThat(exchange.apiKey()).isEqualTo("test-key-123456");
        assertThat(exchange.body().get("model").asText()).isEqualTo("claude-opus-5-5");
        assertThat(exchange.body().get("temperature").asDouble()).isZero();
        JsonNode image = exchange.body().at("/messages/0/content/1/image_url");
        assertThat(image.get("url").asText()).isEqualTo("data:image/png;base64,AQID");
        assertThat(image.get("detail").asText()).isEqualTo("high");
        assertThat(exchange.body().at("/tools/0/function/parameters/properties/strategy/enum/1").asText()).isEqualTo("xpath");
        assertThat(response.usage()).isEqualTo(new TokenUsage(120, 20, 10, 20));
        assertThat(response.requireContent()).isEqualTo("plain answer");
    }

    @Test
    void notFoundDeploymentFailsOverAndStructuredOutputUsesStrictJsonSchema() {
        LocatorAnswer answer = router.chat(LlmRequest.structured(LlmTier.TIER_2, MigrationStage.STAGE_3_CODEGEN,
                "locators", List.of(UserMessage.of("Map it")), LocatorAnswer.class)).requireContent();

        assertThat(answer).isEqualTo(new LocatorAnswer("accessibility", "login"));
        assertThat(exchanges).extracting(Exchange::path).containsExactly(
                "/openai/deployments/gone-deployment/chat/completions",
                "/openai/deployments/claude-sonnet-5-5/chat/completions");
        JsonNode format = exchanges.get(1).body().get("response_format");
        assertThat(format.get("type").asText()).isEqualTo("json_schema");
        assertThat(format.at("/json_schema/strict").asBoolean()).isTrue();
        assertThat(format.at("/json_schema/name").asText()).isEqualTo("LocatorAnswer");
        assertThat(format.at("/json_schema/schema/properties/strategy/type/0").asText()).isEqualTo("string");
        assertThat(format.at("/json_schema/schema/additionalProperties").asBoolean(true)).isFalse();
    }

    @Test
    void deploymentRejectingJsonSchemaIsRetriedWithTheSchemaInThePrompt() {
        LocatorAnswer answer = router.chat(LlmRequest.structured(LlmTier.TIER_3, MigrationStage.STAGE_1_DELTA_ANALYSIS,
                "delta", List.of(UserMessage.of("Map it")), LocatorAnswer.class)).requireContent();

        assertThat(answer.value()).isEqualTo("login");
        assertThat(exchanges).hasSize(2);
        assertThat(exchanges.get(1).body().has("response_format")).isFalse();
        assertThat(exchanges.get(1).body().at("/messages/0/role").asText()).isEqualTo("system");
        assertThat(exchanges.get(1).body().at("/messages/0/content").asText()).contains("JSON Schema");
    }

    private void handle(HttpExchange http) throws IOException {
        String path = http.getRequestURI().getPath();
        JsonNode body = JsonMappers.llm().readTree(http.getRequestBody().readAllBytes());
        exchanges.add(new Exchange(path, http.getRequestHeaders().getFirst("api-key"), body));
        String model = path.split("/")[3];
        if (model.equals("gone-deployment")) {
            respond(http, 404, "{\"error\":{\"message\":\"The API deployment for this resource does not exist.\"}}");
        } else if (model.equals("no-schema-model") && body.has("response_format")) {
            respond(http, 400, "{\"error\":{\"message\":\"response_format of type json_schema is not supported\"}}");
        } else if (body.has("response_format") || model.equals("no-schema-model")) {
            respond(http, 200, ANSWER.formatted(model,
                    JsonMappers.llm().writeValueAsString("{\"strategy\":\"accessibility\",\"value\":\"login\"}")));
        } else {
            respond(http, 200, ANSWER.formatted(model, "\"plain answer\""));
        }
    }

    private static void respond(HttpExchange http, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        http.getResponseHeaders().add("Content-Type", "application/json");
        http.sendResponseHeaders(status, bytes.length);
        http.getResponseBody().write(bytes);
        http.close();
    }
}
