package alex.valker91.testmigrator;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

import java.time.Duration;
import java.util.Map;

public class TestMigrator {
    public static void main(String[] args) {
        String dialHost = "https://ai-proxy.lab.epam.com";
        String dialApiKey = "dial-6w6i0c55hfjava7qbym7dzyy92a";

        // курл для проверки доступных моделей: curl -s -H "api-key: dial-6w6i0c55hfjava7qbym7dzyy92a" https://ai-proxy.lab.epam.com/openai/models | grep -o '"id":"[^"]*"'
        String plannerModelId = "gpt-6.1-sol-2026-09-29";

        String plannerModelUrl = dialHost + "/openai/deployments/" + plannerModelId;

        ChatModel plannerModel = OpenAiChatModel.builder()
                .baseUrl(plannerModelUrl)
                .apiKey(dialApiKey)
                .customHeaders(Map.of("api-key", dialApiKey))
                .modelName(plannerModelId)
                .temperature(0.0)
                .logRequests(true)
                .logResponses(true)
                .timeout(Duration.ofSeconds(60))
                .build();

        System.out.println("--- Отправка запроса в DIAL (" + plannerModelId + ") ---");
        String plannerResponse = plannerModel.chat("Скажи привет Татьяна и скажи какой сегодня день недели и точную дату, дура!");
        System.out.println("--- Ответ получен! ---");
        System.out.println(plannerResponse);




        String workerModelId = "gpt-4.1-mini-2025-04-14";
        String deploymentUrl = dialHost + "/openai/deployments/" + workerModelId;
        ChatModel workerModel = OpenAiChatModel.builder()
                .baseUrl(deploymentUrl)
                .apiKey(dialApiKey)
                .customHeaders(Map.of("api-key", dialApiKey))
                .modelName(workerModelId)
                .temperature(0.0)
                .logRequests(true)
                .logResponses(true)
                .timeout(Duration.ofSeconds(60))
                .build();

        System.out.println("--- Отправка запроса в DIAL (" + workerModelId + ") ---");
        String workerResponse = workerModel.chat("Скажи привет Татьяна и скажи какой сегодня день недели и точную дату, дура!");
        System.out.println("--- Ответ получен! ---");
        System.out.println(workerResponse);
    }

//    public static void main(String[] args) {
//        String dialUrl = "https://ai-proxy.lab.epam.com";
//        String dialApiKey = "dial-6w6i0c55hfjava7qbym7dzyy92a";
//
//        System.out.println("--- 1. Инициализация модели ---");
//        ChatModel model = OpenAiChatModel.builder()
//                .baseUrl(dialUrl)
//                .apiKey(dialApiKey)
//                .modelName("gpt-6.1-sol") // если вернет 404, попробуйте "gpt-5.2-2025-12-11" или "gpt-4.1-mini-2025-04-14"
//                .temperature(0.0)
//                .timeout(Duration.ofSeconds(10))
//                .logRequests(true)  // выведет HTTP-запрос в консоль
//                .logResponses(true) // выведет HTTP-ответ в консоль
//                .build();
//
//        try {
//            System.out.println("--- 2. Отправка тестового запроса ---");
//            String response = model.chat("Say 'pong' if you can read this message");
//
//            System.out.println("--- 3. УСПЕХ! Ответ от модели: ---");
//            System.out.println(response);
//
//        } catch (Exception e) {
//            System.err.println("--- ОШИБКА ПОДКЛЮЧЕНИЯ ---");
//            e.printStackTrace();
//        }
//    }




//    static void main() {
//        String dialUrl = "https://ai-proxy.lab.epam.com/v1";
//        String dialApiKey = "dial-6w6i0c55hfjava7qbym7dzyy92a";
//
//        ChatModel plannerModel = OpenAiChatModel.builder()
//                .baseUrl(dialUrl)
//                .apiKey(dialApiKey)
//                .modelName("gpt-6.1-sol")
//                .temperature(0.0)
//                .logRequests(true) // на этапе отладки полезно включить, чтобы видеть HTTP-запрос и ответ
//                .logResponses(true)
//                .build();
//
//// 2. Инициализируем легкую и быструю модель (Worker)
//// Варианты: "gpt-5-mini-2025-08-07", "gpt-4.1-mini-2025-04-14", "gpt-5.6-terra" или "gpt-5.6-luna"
//        ChatModel workerModel = OpenAiChatModel.builder()
//                .baseUrl(dialUrl)
//                .apiKey(dialApiKey)
//                .modelName("gpt-5-mini-2025-08-07")
//                .temperature(0.0)
//                .logRequests(true)
//                .logResponses(true)
//                .build();
//
//        MigrationPlannerService planner = AiServices.create(MigrationPlannerService.class, plannerModel);
//
//        LocatorWorkerService worker = AiServices.create(LocatorWorkerService.class, workerModel);
//
//        // Исходный Page Object (для примера)
//        String androidPageObject = """
//            public class LoginPage {
//                @AndroidFindBy(id = "com.epam:id/user_email")
//                private WebElement emailInput;
//
//                @AndroidFindBy(id = "com.epam:id/login_button")
//                private WebElement submitButton;
//            }
//        """;
//
//        // Сжатый дамп экрана iOS (отфильтрованный от appium-ios-mcp)
//        String sanitizedIosElements = """
//            [
//              {"type": "XCUIElementTypeTextField", "name": "user_email", "label": "Email"},
//              {"type": "XCUIElementTypeButton", "name": "login_btn", "label": "Sign In"}
//            ]
//        """;
//
//        // === ВЫПОЛНЕНИЕ ПАЙПЛАЙНА ===
//        System.out.println("-> Шаг 1: Топовая модель строит план...");
//        Models.MigrationPlan plan = planner.createPlan(androidPageObject);
//        System.out.println("Найдено элементов для переноса: " + plan.elementsToMap().size());
//
//        System.out.println("-> Шаг 2: Дешёвая модель сопоставляет локаторы...");
//        Models.LocatorMapping mapping = worker.mapLocators(plan.elementsToMap(), sanitizedIosElements);
//
//        System.out.println("\nРезультат маппинга локаторов:");
//        mapping.mappedLocators().forEach((field, locator) ->
//                System.out.println(field + " -> " + locator)
//        );
//    }
}
