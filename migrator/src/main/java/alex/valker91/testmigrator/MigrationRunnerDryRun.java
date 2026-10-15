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
    private final MetricsTracker metrics = new MetricsTracker();

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

    private String askLlmWithLiveOutput(StreamingChatModel model, String prompt, String modelName) {
        System.out.println("\n--- [LLM РАССУЖДАЕТ / ГЕНЕРИРУЕТ ОТВЕТ] ---");
        long startTime = System.currentTimeMillis();

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
                long duration = System.currentTimeMillis() - startTime;
                System.out.println("\n--- [ГЕНЕРАЦИЯ ЗАВЕРШЕНА] ---\n");
                metrics.record(modelName, completeResponse.tokenUsage(), duration);
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
            // [1/4] Читаем тест и вызываем Planner LLM для построения списка шагов (steps)
            System.out.println("\n[1/4] Чтение файла теста и анализ логики через Planner LLM...");
            String testClassSource = findAndReadJavaFile(TESTS_DIR, testClassName + ".java");
            if (testClassSource == null) {
                throw new RuntimeException("Не найден файл теста: " + testClassName + ".java в директории " + TESTS_DIR);
            }

            // ВОТ ЗДЕСЬ ОБЪЯВЛЯЮТСЯ И СОЗДАЮТСЯ STEPS:
            List<TestStep> steps = planTestSteps(testClassSource, testMethodName);
            System.out.println("Построен план из " + steps.size() + " шагов:");
            for (int i = 0; i < steps.size(); i++) {
                System.out.println("  " + (i + 1) + ". " + steps.get(i));
            }

            // [2/4] Запуск клиентов MCP
            System.out.println("\n[2/4] Подключение к Android и iOS через MCP...");
            try (McpSyncClient androidClient = createAndroidClient();
                 McpSyncClient iosClient = createIosClient()) {

                System.out.println("Запускаем приложения на Android и iOS одновременно...");

                // Запускаем Android в фоновом потоке
                CompletableFuture<Void> androidStart = CompletableFuture.runAsync(() -> {
                    try {
                        System.out.println("-> [Android] Старт сессии...");
                        initSession(androidClient, "android", ANDROID_CAPS_PATH);
                        System.out.println("-> [Android] Сессия запущена!");
                    } catch (Exception e) {
                        System.err.println("Ошибка старта Android: " + e.getMessage());
                        throw new RuntimeException(e);
                    }
                });

                // Запускаем iOS в параллельном потоке
                CompletableFuture<Void> iosStart = CompletableFuture.runAsync(() -> {
                    try {
                        System.out.println("-> [iOS] Старт сессии...");
                        initSession(iosClient, "ios", IOS_CAPS_PATH);
                        System.out.println("-> [iOS] Сессия запущена!");
                    } catch (Exception e) {
                        System.err.println("Ошибка старта iOS: " + e.getMessage());
                        throw new RuntimeException(e);
                    }
                });

                // Ждем, пока ОБА приложения откроются на экранах
                CompletableFuture.allOf(androidStart, iosStart).join();
                System.out.println("\n Приложения успешно запущены на обоих устройствах!\n");

                // [3/4] Пошаговое выполнение с получением PageSource и обновлением локаторов
                System.out.println("\n[3/4] Пошаговая синхронная миграция локаторов...");
                for (TestStep step : steps) {
                    processStep(step, androidClient, iosClient);
                }

                System.out.println("\n[4/4] === МИГРАЦИЯ ТЕСТА УСПЕШНО ЗАВЕРШЕНА! ===");
                closeSession(androidClient, "Android");
                closeSession(iosClient, "iOS");


                // ВЫВОД ИНФОГРАФИКИ:
                metrics.printDashboard();
            }

        } catch (Exception e) {
            System.err.println("Ошибка миграции: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void closeSession(McpSyncClient client, String platform) {
        try {
            System.out.println("Закрываем сессию Appium для " + platform + "...");
            client.callTool(new McpSchema.CallToolRequest(
                    "appium_session_management",
                    Map.of("action", "delete")
            ));
            System.out.println("Сессия " + platform + " успешно закрыта.");
        } catch (Exception e) {
            System.err.println("Ошибка при закрытии сессии " + platform + ": " + e.getMessage());
        }
    }

    private void processStep(TestStep step, McpSyncClient androidClient, McpSyncClient iosClient) {
        System.out.println("\n--- Выполняем шаг: " + step.actionType + " | " + step.targetElementDescription + " ---");

        // 1. Получаем сырой Page Source
        String rawAndroidXml = getPageSource(androidClient);
        String rawIosXml = getPageSource(iosClient);

        // 2. Очищаем через XmlSanitizer
        String cleanAndroidXml = XmlSanitizer.sanitize(rawAndroidXml);
        String cleanIosXml = XmlSanitizer.sanitize(rawIosXml);

        System.out.println("  [XML Stats] Android: " + rawAndroidXml.length() + "b -> " + cleanAndroidXml.length() + "b"
                + " | iOS: " + rawIosXml.length() + "b -> " + cleanIosXml.length() + "b");

        // 3. Worker LLM получает чистый, сжатый XML
        IosLocator iosLocator = resolveIosLocator(step, cleanAndroidXml, cleanIosXml);
        System.out.println("  -> Найден iOS локатор: [" + iosLocator.strategy + "] = \"" + iosLocator.value + "\"");

        // 4. Записываем в Page Object
        if (step.pageClassName != null && !step.pageClassName.isBlank()) {
            injectLocatorIntoPageObject(step.pageClassName, step.elementIdentifier, iosLocator);
        }

        // 5. Выполняем шаг на обоих симуляторах
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
                                 "targetElementDescription": "Краткое описание элемента",
                                 "pageClassName": "Имя класса страницы (например LoginPage, HomeScreen)",
                                 "elementIdentifier": "Имя переменной WebElement поля или метода (например emailField, passwordField, loginButton, invalidEmailError)",
                                 "androidStrategy": "accessibility id | id | xpath",
                                 "androidSelector": "предполагаемый или известный селектор android",
                                 "inputValue": "значение для ввода, если actionType=type, иначе null"
                               }
                             ]
            """, methodName, testSource);

        System.out.println(">> Отправляем промпт в Planner (" + PLANNER_MODEL_ID + ")...");

        // Вызываем стриминг: текст и мысли модели будут печататься сразу!
        String rawResponse = askLlmWithLiveOutput(streamingPlannerModel, prompt, PLANNER_MODEL_ID);

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

        long startTime = System.currentTimeMillis();

        // Вызываем модель через chat(ChatRequest), чтобы достать ChatResponse с токенами:
        dev.langchain4j.model.chat.response.ChatResponse response = workerModel.chat(
                dev.langchain4j.model.chat.request.ChatRequest.builder()
                        .messages(dev.langchain4j.data.message.UserMessage.from(prompt))
                        .build()
        );

        long duration = System.currentTimeMillis() - startTime;
        // Записываем статистику по Worker-модели:
        metrics.record(WORKER_MODEL_ID, response.tokenUsage(), duration);

        String json = cleanMarkdown(response.aiMessage().text());
        try {
            return jsonParser.readValue(json, IosLocator.class);
        } catch (Exception e) {
            return new IosLocator("accessibility id", step.androidSelector);
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

            // 1. Проверяем и добавляем импорт iOSXCUITFindBy, если его еще нет
            if (!content.contains("import io.appium.java_client.pagefactory.iOSXCUITFindBy;")) {
                content = content.replaceFirst(
                        "(import io\\.appium\\.java_client\\.pagefactory\\.AndroidFindBy;)",
                        "$1\nimport io.appium.java_client.pagefactory.iOSXCUITFindBy;"
                );
            }

            // Формируем чистую боевую аннотацию
            String annotationParam = convertStrategyToAnnotationParam(locator.strategy);
            String iosAnnotation = String.format("@iOSXCUITFindBy(%s = \"%s\")\n    ", annotationParam, locator.value);

            // 2. Если такая аннотация для этого элемента уже есть — не дублируем
            if (content.contains("@iOSXCUITFindBy(" + annotationParam + " = \"" + locator.value + "\")")) {
                System.out.println("  [Code Skipped] Аннотация уже присутствует в " + fileName);
                return;
            }

            // 3. Стратегия поиска: ищем соответствующий @AndroidFindBy
            // Пытаемся найти поле по названию или по связанному методу
            boolean inserted = false;

            // Вариант А: Ищем поле, связанное с методом (например, enterEmail -> emailField / email)
            String fieldRegex = "(?i)private\\s+WebElement\\s+([a-zA-Z0-9_]*" + extractBaseName(elementIdentifier) + "[a-zA-Z0-9_]*);";
            Matcher fieldMatcher = Pattern.compile(fieldRegex).matcher(content);

            if (fieldMatcher.find()) {
                String fieldName = fieldMatcher.group(1);
                // Вставляем @iOSXCUITFindBy прямо перед @AndroidFindBy этого поля
                String targetRegex = "(@AndroidFindBy\\([^\\)]+\\)\\s+private\\s+WebElement\\s+" + Pattern.quote(fieldName) + ";)";
                if (content.matches("(?s).*" + targetRegex + ".*")) {
                    content = content.replaceFirst(targetRegex, iosAnnotation + "$1");
                    inserted = true;
                }
            }

            // Вариант Б: Если точное поле не нашли, вставляем перед строкой с @AndroidFindBy,
            // где значение селектора совпадает с locator.value (или accessibility id)
            if (!inserted) {
                String byValueRegex = "(@AndroidFindBy\\([^\\n]*\"" + Pattern.quote(locator.value) + "\"[^\\n]*\\))";
                if (Pattern.compile(byValueRegex).matcher(content).find()) {
                    content = content.replaceFirst(byValueRegex, iosAnnotation + "$1");
                    inserted = true;
                }
            }

            // Вариант В: Fallback прямо над методом
            if (!inserted && elementIdentifier != null && content.contains(elementIdentifier)) {
                content = content.replaceFirst("(?m)^(\\s*.*" + Pattern.quote(elementIdentifier) + ")", "    " + iosAnnotation + "$1");
                inserted = true;
            }

            if (inserted) {
                Files.writeString(path, content);
                System.out.println("  [Code Updated] Реальный локатор @" + iosAnnotation.trim() + " добавлен в " + fileName);
            }

        } catch (IOException e) {
            System.err.println("Ошибка записи в Page Object: " + e.getMessage());
        }
    }

    private String extractBaseName(String identifier) {
        if (identifier == null) return "";
        // Убираем enter, click, is, get, Displayed, Button, Field
        return identifier.replaceAll("^(enter|click|is|get|open)", "")
                .replaceAll("(Displayed|ErrorDisplayed)$", "")
                .toLowerCase();
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

        if (result.content() != null && !result.content().isEmpty()) {
            var first = result.content().get(0);
            if (first instanceof McpSchema.TextContent textContent) {
                return textContent.text();
            }
        }
        return result.content() != null ? result.content().toString() : "";
    }

//    private McpSyncClient createAndroidClient() {
//        return buildClient(ANDROID_CAPS_PATH);
//    }
//
//    private McpSyncClient createIosClient() {
//        return buildClient(IOS_CAPS_PATH);
//    }
//
//    private McpSyncClient buildClient(String capsPath) {
//        ServerParameters params = ServerParameters.builder("npx")
//                .args("-y", "appium-mcp@latest")
//                .env(Map.of("CAPABILITIES_CONFIG", capsPath))
//                .build();
//
//        McpJsonMapper jsonMapper = new JacksonMcpJsonMapper(new JsonMapper());
//        StdioClientTransport transport = new StdioClientTransport(params, jsonMapper, 10 * 1024 * 1024);
//
//        McpSyncClient client = McpClient.sync(transport)
//                .requestTimeout(Duration.ofSeconds(120))
//                .build();
//
//        client.initialize();
//        return client;
//    }

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

        // Используем второй конструктор со скриншота: с запасом памяти 10 МБ для XML
        McpJsonMapper jsonMapper = new JacksonMcpJsonMapper(new JsonMapper());
        StdioClientTransport transport = new StdioClientTransport(params, jsonMapper, 10 * 1024 * 1024);

        McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(120))
                .build();

        client.initialize();
        return client;
    }

}
