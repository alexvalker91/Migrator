package alex.valker91.testmigrator;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MigrationRunnerDryRun {
    // Пути к репозиторию
    private static final String PROJECT_ROOT = "/Users/Aliaksandr_Kreyer/projects/repositories/";
    private static final String ANDROID_CAPS_PATH = PROJECT_ROOT + "util/capabilities-android.json";
    private static final String IOS_CAPS_PATH = PROJECT_ROOT + "util/capabilities-ios.json";

    // Пути к исходникам тестов и страниц
    private static final String TESTS_DIR = PROJECT_ROOT + "Migrator/migrator/src/test/java/";
    private static final String PAGES_DIR = PROJECT_ROOT + "Migrator/migrator/src/main/java/";

    // Настройки EPAM DIAL
    private static final String DIAL_HOST = "https://ai-proxy.lab.epam.com";
    private static final String DIAL_API_KEY = "dial-6w6i0c55hfjava7qbym7dzyy92a";
    private static final String PLANNER_MODEL_ID = "gpt-6.1-sol-2026-09-29";
    private static final String WORKER_MODEL_ID = "gpt-4.1-mini-2025-04-14";

//    private final ChatModel plannerModel;
    private final ChatModel workerModel;
    private final ObjectMapper jsonParser = new ObjectMapper();

    private final StreamingChatModel streamingPlannerModel;
    public MigrationRunnerDryRun() {
        this.streamingPlannerModel = OpenAiStreamingChatModel.builder()
                .baseUrl(DIAL_HOST + "/openai/deployments/" + PLANNER_MODEL_ID)
                .apiKey(DIAL_API_KEY)
                .customHeaders(Map.of("api-key", DIAL_API_KEY))
                .modelName(PLANNER_MODEL_ID)
                .temperature(0.0)
                .timeout(Duration.ofSeconds(120))
                .build();

//        this.plannerModel = OpenAiChatModel.builder()
//                .baseUrl(DIAL_HOST + "/openai/deployments/" + PLANNER_MODEL_ID)
//                .apiKey(DIAL_API_KEY)
//                .customHeaders(Map.of("api-key", DIAL_API_KEY))
//                .modelName(PLANNER_MODEL_ID)
//                .temperature(0.0)
//                .timeout(Duration.ofSeconds(120))
//                .build();

        this.workerModel = OpenAiChatModel.builder()
                .baseUrl(DIAL_HOST + "/openai/deployments/" + WORKER_MODEL_ID)
                .apiKey(DIAL_API_KEY)
                .customHeaders(Map.of("api-key", DIAL_API_KEY))
                .modelName(WORKER_MODEL_ID)
                .temperature(0.0)
                .timeout(Duration.ofSeconds(60))
                .build();
    }

    private String askLlmWithLiveOutput(StreamingChatModel model, String prompt) {
        System.out.println("\n--- [LLM РАССУЖДАЕТ / ГЕНЕРИРУЕТ ОТВЕТ] ---");

        StringBuilder fullResponse = new StringBuilder();
        CompletableFuture<String> future = new CompletableFuture<>();

        model.chat(prompt, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String token) {
                // Печатает каждое слово/токен сразу в консоль в момент генерации!
                System.out.print(token);
                System.out.flush();
                fullResponse.append(token);
            }

            @Override
            public void onCompleteResponse(dev.langchain4j.model.chat.response.ChatResponse completeResponse) {
                System.out.println("\n--- [ГЕНЕРАЦИЯ ЗАВЕРШЕНА] ---\n");
                future.complete(fullResponse.toString());
            }

            @Override
            public void onError(Throwable error) {
                System.err.println("\n[ОШИБКА LLM]: " + error.getMessage());
                future.completeExceptionally(error);
            }
        });

        try {
            // Ждем завершения генерации до 120 секунд
            return future.get(120, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("Ошибка или таймаут ожидания стриминга LLM", e);
        }
    }

    public static void main(String[] args) {
        MigrationRunnerDryRun runner = new MigrationRunnerDryRun();

        // Входная точка: просто передаем класс и имя метода
        runner.migrateTest("LoginTest", "loginNegativeTest");
    }

    public void migrateTest(String testClassName, String testMethodName) {
        System.out.println("==================================================");
        System.out.println("  СТАРТ МИГРАЦИИ: " + testClassName + " -> " + testMethodName);
        System.out.println("==================================================");

        try {
            // 1. Находим и считываем исходный код тестового класса
            String testClassSource = findAndReadJavaFile(TESTS_DIR, testClassName + ".java");
            if (testClassSource == null) {
                throw new RuntimeException("Не найден файл теста: " + testClassName + ".java");
            }

            // 2. Planner LLM разбирает тест на список шагов (Actions)
            System.out.println("\n[1/4] Анализ логики теста через Planner LLM...");
            List<TestStep> steps = planTestSteps(testClassSource, testMethodName);
            System.out.println("Построен план из " + steps.size() + " шагов:");
            for (int i = 0; i < steps.size(); i++) {
                System.out.println("  " + (i + 1) + ". " + steps.get(i));
            }

            // 3. Запуск двух параллельных MCP-сессий
            System.out.println("\n[2/4] Подключение к Android и iOS через MCP...");
            try (McpSyncClient androidClient = createAndroidClient();
                 McpSyncClient iosClient = createIosClient()) {

                initSession(androidClient, "android", ANDROID_CAPS_PATH);
                initSession(iosClient, "ios", IOS_CAPS_PATH);

                // 4. Пошаговое выполнение с получением PageSource и обновлением локаторов
                System.out.println("\n[3/4] Пошаговая синхронная миграция локаторов...");
                for (TestStep step : steps) {
                    processStep(step, androidClient, iosClient);
                }

                System.out.println("\n[4/4] === МИГРАЦИЯ ТЕСТА УСПЕШНО ЗАВЕРШЕНА! ===");
            }

        } catch (Exception e) {
            System.err.println("Ошибка миграции: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void processStep(TestStep step, McpSyncClient androidClient, McpSyncClient iosClient) {
        System.out.println("\n--- Выполняем шаг: " + step.actionType + " | " + step.targetElementDescription + " ---");

        // 1. Получаем Page Source с обоих устройств
        String androidXml = getPageSource(androidClient);
        String iosXml = getPageSource(iosClient);

        // 2. Worker LLM сопоставляет текущее состояние и находит iOS-локатор
        IosLocator iosLocator = resolveIosLocator(step, androidXml, iosXml);
        System.out.println("  -> Найден iOS локатор: [" + iosLocator.strategy + "] = \"" + iosLocator.value + "\"");

        // 3. Записываем найденный локатор в Page Object класс на диске
        if (step.pageClassName != null && !step.pageClassName.isBlank()) {
            injectLocatorIntoPageObject(step.pageClassName, step.elementIdentifier, iosLocator);
        }

        // 4. Синхронно выполняем действие на обеих платформах, чтобы продвинуть UI дальше
        executeStepOnDevice(androidClient, step.actionType, step.androidStrategy, step.androidSelector, step.inputValue);
        executeStepOnDevice(iosClient, step.actionType, iosLocator.strategy, iosLocator.value, step.inputValue);
    }

    // =========================================================================
    // LLM Planner: разбор тестового метода на последовательность шагов
    // =========================================================================

    private List<TestStep> planTestSteps(String testSource, String methodName) {
        String prompt = String.format("""
            Ты senior SDET архитектор. Проанализируй этот Java TestNG тест (метод '%s') и разбей его на плоский список шагов.
            
            Код теста:
            %s
            
            Сначала кратко распиши свои мысли по шагам, а в конце выдай валидный JSON массив шагов в блоке ```json ... ```:
            [
              {
                "actionType": "tap | type | assert_visible",
                "targetElementDescription": "Краткое описание элемента (например: кнопка Login в меню)",
                "pageClassName": "Имя класса страницы (например LoginPage, HomeScreen)",
                "elementIdentifier": "Имя переменной или метода элемента (например loginBtn, emailInput)",
                "androidStrategy": "accessibility id | id | xpath",
                "androidSelector": "предполагаемый или известный селектор android",
                "inputValue": "значение для ввода, если actionType=type, иначе null"
              }
            ]
            """, methodName, testSource);

        System.out.println(">> Отправляем промпт в Planner (" + PLANNER_MODEL_ID + ")...");

        // Вызываем стриминг: текст и мысли модели будут печататься сразу!
        String rawResponse = askLlmWithLiveOutput(streamingPlannerModel, prompt);

        // Извлекаем только JSON блок из ответа
        String jsonResponse = extractJsonBlock(rawResponse);
        try {
            TestStep[] stepArray = jsonParser.readValue(jsonResponse, TestStep[].class);
            return Arrays.asList(stepArray);
        } catch (Exception e) {
            System.err.println("Не удалось распарсить JSON шагов. Ответ был:\n" + rawResponse);
            throw new RuntimeException(e);
        }
    }

    private String extractJsonBlock(String text) {
        Pattern p = Pattern.compile("```(?:json)?\\s*([\\[\\{].*?[\\]\\}])\\s*```", Pattern.DOTALL);
        Matcher m = p.matcher(text);
        if (m.find()) {
            return m.group(1).trim();
        }
        return cleanMarkdown(text);
    }

    // =========================================================================
    // LLM Worker: поиск точного локатора в iOS XML
    // =========================================================================

    private IosLocator resolveIosLocator(TestStep step, String androidXml, String iosXml) {
        String prompt = String.format("""
                Ты Appium эксперт. Найди лучший локатор для iOS, соответствующий действию на Android.
                
                Действие: %s
                Описание: %s
                Android подсказка: strategy=%s, selector=%s
                
                XML Android (текущий экран):
                %s
                
                XML iOS (текущий экран):
                %s
                
                Правила выбора локатора iOS:
                - Приоритет: accessibility id (name/label) > -ios class chain > -ios predicate string > xpath.
                - Селектор должен быть надежным и уникальным.
                
                Ответь строго в формате JSON:
                {
                  "strategy": "accessibility id | -ios class chain | -ios predicate string | xpath",
                  "value": "сам_селектор"
                }
                """, step.actionType, step.targetElementDescription, step.androidStrategy, step.androidSelector,
                truncate(androidXml), truncate(iosXml));

        String json = cleanMarkdown(workerModel.chat(prompt));
        try {
            return jsonParser.readValue(json, IosLocator.class);
        } catch (Exception e) {
            return new IosLocator("accessibility id", step.androidSelector); // fallback
        }
    }

    // =========================================================================
    // Инъекция найденного локатора в исходники Page Object
    // =========================================================================

    private void injectLocatorIntoPageObject(String pageClassName, String elementIdentifier, IosLocator locator) {
        String fileName = pageClassName.endsWith(".java") ? pageClassName : pageClassName + ".java";
        Path path = findFilePath(PAGES_DIR, fileName);

        if (path == null) {
            System.out.println("  [Skip] Файл " + fileName + " не найден в " + PAGES_DIR);
            return;
        }

        try {
            String content = Files.readString(path);
            String annotationOrComment = String.format(
                    "\n    // [Migrated iOS Locator]\n    // @iOSXCUITFindBy(%s = \"%s\")\n",
                    convertStrategyToAnnotationParam(locator.strategy), locator.value
            );

            // Если есть поле или метод с таким идентификатором, добавляем аннотацию/локатор перед ним
            if (elementIdentifier != null && content.contains(elementIdentifier)) {
                content = content.replaceFirst("(?m)^(\\s*.*" + Pattern.quote(elementIdentifier) + ")", annotationOrComment + "$1");
                Files.writeString(path, content);
                System.out.println("  [Code Updated] Локатор сохранен в: " + path.getFileName());
            }
        } catch (IOException e) {
            System.err.println("Ошибка записи в Page Object: " + e.getMessage());
        }
    }

    private String convertStrategyToAnnotationParam(String strategy) {
        return switch (strategy.toLowerCase()) {
            case "accessibility id" -> "accessibility";
            case "-ios class chain" -> "iOSClassChain";
            case "-ios predicate string" -> "iOSNsPredicate";
            default -> "xpath";
        };
    }

    // =========================================================================
    // Исполнение шагов на устройствах через Appium MCP
    // =========================================================================

    private void executeStepOnDevice(McpSyncClient client, String action, String strategy, String selector, String value) {
        try {
            if ("assert_visible".equalsIgnoreCase(action)) {
                // Просто проверка наличия элемента
                client.callTool(new McpSchema.CallToolRequest("appium_find_element", Map.of("strategy", strategy, "selector", selector)));
                return;
            }

            McpSchema.CallToolResult findRes = client.callTool(new McpSchema.CallToolRequest(
                    "appium_find_element",
                    Map.of("strategy", strategy, "selector", selector)
            ));

            String uuid = extractElementUuid(findRes.content().toString());

            if ("tap".equalsIgnoreCase(action) || "click".equalsIgnoreCase(action)) {
                client.callTool(new McpSchema.CallToolRequest("appium_gesture", Map.of("action", "tap", "elementUUID", uuid)));
            } else if ("type".equalsIgnoreCase(action)) {
                client.callTool(new McpSchema.CallToolRequest("appium_set_value", Map.of("elementUUID", uuid, "text", value != null ? value : "")));
            }
        } catch (Exception e) {
            System.err.println("  [Warn] Ошибка выполнения действия [" + action + "] по селектору " + selector + ": " + e.getMessage());
        }
    }

    private void initSession(McpSyncClient client, String platform, String capsPath) throws IOException {
        String capsJson = Files.readString(Path.of(capsPath));
        client.callTool(new McpSchema.CallToolRequest(
                "appium_session_management",
                Map.of("action", "create", "platform", platform, "capabilities", capsJson)
        ));
    }

    private String getPageSource(McpSyncClient client) {
        McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest("appium_get_page_source", Map.of()));
        return result.content().toString();
    }

    private McpSyncClient createAndroidClient() {
        return buildClient(ANDROID_CAPS_PATH);
    }

    private McpSyncClient createIosClient() {
        return buildClient(IOS_CAPS_PATH);
    }

    private McpSyncClient buildClient(String capsPath) {
        ServerParameters params = ServerParameters.builder("npx")
                .args("-y", "appium-mcp@latest")
                .env(Map.of("CAPABILITIES_CONFIG", capsPath))
                .build();

        McpJsonMapper jsonMapper = new JacksonMcpJsonMapper(new JsonMapper());
        StdioClientTransport transport = new StdioClientTransport(params, jsonMapper, 10 * 1024 * 1024);

        McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(120))
                .build();

        client.initialize();
        return client;
    }

    // =========================================================================
    // Утилиты
    // =========================================================================

    private Path findFilePath(String baseDir, String fileName) {
        try (var stream = Files.walk(Path.of(baseDir))) {
            return stream.filter(p -> p.getFileName().toString().equals(fileName)).findFirst().orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private String findAndReadJavaFile(String baseDir, String fileName) {
        Path path = findFilePath(baseDir, fileName);
        if (path != null) {
            try {
                return Files.readString(path);
            } catch (IOException ignored) {}
        }
        return null;
    }

    private String extractElementUuid(String rawResponse) {
        Matcher m = Pattern.compile("([a-f0-9\\-]{16,36})", Pattern.CASE_INSENSITIVE).matcher(rawResponse);
        return m.find() ? m.group(1) : rawResponse.replaceAll("[^a-zA-Z0-9\\-]", "");
    }

    private String cleanMarkdown(String text) {
        return text.replaceAll("```json", "").replaceAll("```", "").trim();
    }

    private String truncate(String text) {
        return (text != null && text.length() > 30000) ? text.substring(0, 30000) + "\n<!-- TRUNCATED -->" : text;
    }

    // Модели данных для LLM
    public static class TestStep {
        public String actionType;
        public String targetElementDescription;
        public String pageClassName;
        public String elementIdentifier;
        public String androidStrategy;
        public String androidSelector;
        public String inputValue;

        @Override
        public String toString() {
            return "[" + actionType + "] " + targetElementDescription + " (Page: " + pageClassName + ")";
        }
    }

    public static class IosLocator {
        public String strategy;
        public String value;
        public IosLocator() {}
        public IosLocator(String strategy, String value) {
            this.strategy = strategy;
            this.value = value;
        }
    }
}
