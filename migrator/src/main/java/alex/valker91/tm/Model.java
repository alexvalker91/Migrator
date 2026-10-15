package alex.valker91.tm;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

import java.time.Duration;
import java.util.Map;

public class Model {

    // курл для проверки доступных моделей: curl -s -H "api-key: dial-6w6i0c55hfjava7qbym7dzyy92a" https://ai-proxy.lab.epam.com/openai/models | grep -o '"id":"[^"]*"'

    private String dialHost = "https://ai-proxy.lab.epam.com";
    private String dialApiKey = "dial-6w6i0c55hfjava7qbym7dzyy92a";

    private String plannerModelId = "gpt-6.1-sol-2026-09-29";
    private String plannerModelUrl = dialHost + "/openai/deployments/" + plannerModelId;

    private String workerModelId = "gpt-4.1-mini-2025-04-14";
    private String deploymentUrl = dialHost + "/openai/deployments/" + workerModelId;












//    static void main() {
//        // курл для проверки доступных моделей: curl -s -H "api-key: dial-6w6i0c55hfjava7qbym7dzyy92a" https://ai-proxy.lab.epam.com/openai/models | grep -o '"id":"[^"]*"'
//
//
//        String dialHost = "https://ai-proxy.lab.epam.com";
//        String dialApiKey = "dial-6w6i0c55hfjava7qbym7dzyy92a";
//
//        String plannerModelId = "gpt-6.1-sol-2026-09-29";
//        String plannerModelUrl = dialHost + "/openai/deployments/" + plannerModelId;
//
//        String workerModelId = "gpt-4.1-mini-2025-04-14";
//        String deploymentUrl = dialHost + "/openai/deployments/" + workerModelId;
//
//        ChatModel plannerModel = OpenAiChatModel.builder()
//                .baseUrl(plannerModelUrl)
//                .apiKey(dialApiKey)
//                .customHeaders(Map.of("api-key", dialApiKey))
//                .modelName(plannerModelId)
//                .temperature(0.0)
//                .logRequests(true)
//                .logResponses(true)
//                .timeout(Duration.ofSeconds(60))
//                .build();
//
//        String plannerResponse = plannerModel.chat("привет, как дела?");
//        System.out.println(plannerResponse);
//
//        ChatModel workerModel = OpenAiChatModel.builder()
//                .baseUrl(deploymentUrl)
//                .apiKey(dialApiKey)
//                .customHeaders(Map.of("api-key", dialApiKey))
//                .modelName(workerModelId)
//                .temperature(0.0)
//                .logRequests(true)
//                .logResponses(true)
//                .timeout(Duration.ofSeconds(60))
//                .build();
//
//        String workerResponse = workerModel.chat("привет, как дела?");
//        System.out.println(workerResponse);
//    }
}
