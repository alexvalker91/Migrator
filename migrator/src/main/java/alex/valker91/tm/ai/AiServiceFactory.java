package alex.valker91.tm.ai;

import alex.valker91.tm.config.MigrationConfig;
import alex.valker91.tm.metrics.MetricsTracker;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;

import java.time.Duration;
import java.util.List;
import java.util.Map;

public class AiServiceFactory {

    public static PlannerAiService createPlannerService(MetricsTracker metrics) {
        ChatModel model = buildModel(MigrationConfig.PLANNER_MODEL_ID, metrics, Duration.ofSeconds(120));
        return AiServices.builder(PlannerAiService.class)
                .chatModel(model)
                .build();
    }

    public static LocatorResolverAiService createLocatorResolverService(MetricsTracker metrics) {
        ChatModel model = buildModel(MigrationConfig.WORKER_MODEL_ID, metrics, Duration.ofSeconds(60));
        return AiServices.builder(LocatorResolverAiService.class)
                .chatModel(model)
                .build();
    }

    private static ChatModel buildModel(String modelId, MetricsTracker metrics, Duration timeout) {
        return OpenAiChatModel.builder()
                .baseUrl(MigrationConfig.DIAL_HOST + "/openai/deployments/" + modelId)
                .apiKey(MigrationConfig.DIAL_API_KEY)
                .customHeaders(Map.of("api-key", MigrationConfig.DIAL_API_KEY))
                .modelName(modelId)
                .temperature(0.0)
                .timeout(timeout)
                .listeners(List.of(new ChatModelListener() {
                    @Override
                    public void onRequest(ChatModelRequestContext requestContext) {
                        // Запоминаем время старта запроса в атрибуты
                        requestContext.attributes().put("startTime", System.currentTimeMillis());
                    }

                    @Override
                    public void onResponse(ChatModelResponseContext responseContext) {
                        Long startTime = (Long) responseContext.attributes().get("startTime");
                        long durationMs = startTime != null ? System.currentTimeMillis() - startTime : 0L;

                        // Передаем токены и длительность
                        metrics.record(
                                modelId,
                                responseContext.chatResponse().tokenUsage(),
                                durationMs
                        );
                    }
                }))
                .build();
    }
}