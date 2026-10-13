package alex.valker91.util;

public class Util {


//package com.epam.migration.model;
//
//import java.util.List;
//import java.util.Map;
//
//public class Models {
//    public record ElementNeedLocator(String fieldName, String androidLocator, String intent) {}
//
//    public record MigrationPlan(
//            List<ElementNeedLocator> elementsToMap,
//            List<String> iosSpecificFixes
//    ) {}
//
//    public record LocatorMapping(
//            // Map: имя переменной -> готовая строка аннотации, например "@iOSXCUITFindBy(accessibility = \"...\")"
//            Map<String, String> mappedLocators
//    ) {}
//}






//package com.epam.migration.services;
//
//import com.epam.migration.model.Models.*;
//        import dev.langchain4j.service.SystemMessage;
//import dev.langchain4j.service.UserMessage;
//import dev.langchain4j.service.V;
//
//// 1. ТОПОВАЯ МОДЕЛЬ (PLANNER)
//public interface MigrationPlannerService {
//    @SystemMessage("Ты QA Architect. Анализируешь Android Page Objects и строишь строгий план миграции на iOS.")
//    @UserMessage("""
//        Проанализируй Java Page Object под Android:
//        {{pageObjectCode}}
//
//        Определи список элементов, требующих подбора локаторов под iOS.
//        """)
//    MigrationPlan createPlan(@V("pageObjectCode") String pageObjectCode);
//}
//
//// 2. ДЕШЕВАЯ МОДЕЛЬ (WORKER)
//public interface LocatorWorkerService {
//    @SystemMessage("Ты QA Automation Engineer. Подбираешь точные аннотации @iOSXCUITFindBy по дампу экрана iOS.")
//    @UserMessage("""
//        Элементы для маппинга:
//        {{elements}}
//
//        Сжатые интерактивные элементы экрана iOS:
//        {{iosElements}}
//
//        Сгенерируй подходящие аннотации @iOSXCUITFindBy. Приоритет: accessibility, predicate, classChain.
//        """)
//    LocatorMapping mapLocators(
//            @V("elements") List<ElementNeedLocator> elements,
//            @V("iosElements") String iosElements
//    );
//}





//package com.epam.migration;
//
//import com.epam.migration.model.Models.*;
//        import com.epam.migration.services.*;
//        import dev.langchain4j.model.chat.ChatLanguageModel;
//import dev.langchain4j.model.openai.OpenAiChatModel;
//import dev.langchain4j.service.AiServices;
//
//public class DialTestMigrator {
//
//    public static void main(String[] args) {
//        String dialUrl = "https://ai-proxy.lab.epam.com/v1";
//        String dialApiKey = "dial-6w6i0c55hfjava7qbym7dzyy92a";
//
//        // 1. Инициализируем дорогую модель (Planner)
//        ChatLanguageModel plannerModel = OpenAiChatModel.builder()
//                .baseUrl(dialUrl)
//                .apiKey(dialApiKey)
//                .modelName("anthropic.claude-3-5-sonnet") // или gpt-4o
//                .temperature(0.0)
//                .logRequests(false)
//                .build();
//
//        // 2. Инициализируем дешевую модель (Worker)
//        ChatLanguageModel workerModel = OpenAiChatModel.builder()
//                .baseUrl(dialUrl)
//                .apiKey(dialApiKey)
//                .modelName("anthropic.claude-3-5-haiku") // или gpt-4o-mini
//                .temperature(0.0)
//                .logRequests(false)
//                .build();
//
//        // 3. Создаем AI-сервисы через AiServices фабрику
//        MigrationPlannerService planner = AiServices.create(MigrationPlannerService.class, plannerModel);
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
//        MigrationPlan plan = planner.createPlan(androidPageObject);
//        System.out.println("Найдено элементов для переноса: " + plan.elementsToMap().size());
//
//        System.out.println("-> Шаг 2: Дешёвая модель сопоставляет локаторы...");
//        LocatorMapping mapping = worker.mapLocators(plan.elementsToMap(), sanitizedIosElements);
//
//        System.out.println("\nРезультат маппинга локаторов:");
//        mapping.mappedLocators().forEach((field, locator) ->
//                System.out.println(field + " -> " + locator)
//        );
//    }
//}



//import dev.langchain4j.mcp.McpToolProvider;
//import dev.langchain4j.mcp.client.DefaultMcpClient;
//import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
//
//// 1. Описываем запуск вашего appium-ios-mcp прямо как в конфиге VS Code
//StdioMcpTransport transport = new StdioMcpTransport.Builder()
//        .command(List.of("npx", "-y", "appium-mcp@latest"))
//        .environment(Map.of(
//                "CAPABILITIES_CONFIG", "/Users/Aliaksandr_Kreyer/projects/repositories/util/capabilities-ios.json"
//        ))
//        .build();
//
//// 2. Создаем MCP-клиент
//McpClient iosMcpClient = new DefaultMcpClient.Builder()
//        .transport(transport)
//        .build();
//
//// 3. Превращаем его методы в инструменты для модели
//McpToolProvider toolProvider = McpToolProvider.builder()
//        .mcpClients(List.of(iosMcpClient))
//        .build();
//
//// 4. Отдаем эти инструменты нашему AI-сервису в LangChain4j
//IosAgent agent = AiServices.builder(IosAgent.class)
//        .chatLanguageModel(workerModel) // Модель из DIAL
//        .toolProvider(toolProvider)     // Теперь модель умеет сама кликать в симуляторе iOS!
//        .build();






//┌────────────────────────────────────────────────────────────────────────────────────────┐
//            │ 1. АРХИТЕКТУРНЫЙ АНАЛИЗ (ФЛАГМАН / PLANNER)                                            │
//            │                                                                                        │
//            │  [Android Page Object / TestNG]                                                        │
//            │               │                                                                        │
//            │               ▼                                                                        │
//            │   ┌───────────────────────┐                                                            │
//            │   │  Claude 3.5 Sonnet    │ ── "Вот структура теста. Нам нужны селекторы для:          │
//            │   │     (Planner LLM)     │     1. email_field (input)                                 │
//            │   └───────────┬───────────┘     2. login_btn (submit)                                  │
//            │               │                 Особые условия: скрыть клавиатуру после ввода."        │
//            └───────────────┼────────────────────────────────────────────────────────────────────────┘
//            │ (Выдаёт компактный JSON-план миграции)
//            ▼
//            ┌────────────────────────────────────────────────────────────────────────────────────────┐
//            │ 2. СИНХРОНИЗАЦИЯ ЭКРАНОВ ЧЕРЕЗ MCP (ИНСТРУМЕНТЫ)                                       │
//            │                                                                                        │
//            │   [appium-android-mcp]               [appium-ios-mcp]                                  │
//            │         │                                  │                                           │
//            │         ▼ (Воспроизводит шаг)              ▼ (Открывает тот же экран)                  │
//            │   [Android Эмулятор]                 [iOS Симулятор]                                   │
//            │                                            │                                           │
//            │                                            ▼ getPageSource()                           │
//            │                                   [Сырой XML 300+ КБ]                                  │
//            │                                            │                                           │
//            │                                            ▼ (Локальный фильтр в Java)                 │
//            │                                  [Сжатый JSON 2 КБ] ◄─── (Экономия 95% токенов!)       │
//            └────────────────────────────────────────────┼───────────────────────────────────────────┘
//            │
//            ▼
//            ┌────────────────────────────────────────────────────────────────────────────────────────┐
//            │ 3. МАСШТАБИРУЕМАЯ РАБОТА (ДЕШЁВЫЙ WORKER)                                              │
//            │                                                                                        │
//            │   Planner говорит Воркеру:                                                             │
//            │   "Сопоставь поля email_field и login_btn со сжатым экраном iOS и сформируй аннотации"│
//            │                                                                                        │
//            │               │                                                                        │
//            │               ▼                                                                        │
//            │   ┌───────────────────────┐                                                            │
//            │   │   Claude 3.5 Haiku    │ ── Находит: user_email -> accessibility = "email_input"    │
//            │   │     (Worker LLM)      │             login_btn  -> predicate = "label == 'Log In'" │
//            │   └───────────┬───────────┘                                                            │
//            │               │                                                                        │
//            │               ▼ (Формирует финальный код класса)                                       │
//            │   [Готовый кроссплатформенный Java Page Object: @AndroidFindBy + @iOSXCUITFindBy]      │
//            └────────────────────────────────────────────────────────────────────────────────────────┘
//
//
//
//
//
//
//
//
//
//    Planner (Sonnet)       Worker (Haiku)       appium-android-mcp    appium-ios-mcp
//      │                      │                      │                   │
//              │ 1. Анализирует       │                      │                   │
//            │    Android Java код  │                      │                   │
//            │─────────────────────>│                      │                   │
//            │   "ПЛАН: найти       │                      │                   │
//            │    login & pass      │                      │                   │
//            │    для экрана Login" │                      │                   │
//            │                      │                      │                   │
//            │                      │ 2. Узнать контекст   │                   │
//            │                      │    (если нужно)      │                   │
//            │                      │─────────────────────>│                   │
//            │                      │    "Кликни на логин" │                   │
//            │                      │                      │                   │
//            │                      │ 3. Запустить экран на iOS                │
//            │                      │─────────────────────────────────────────>│
//            │                      │    "Перейди на экран Login"              │
//            │                      │                                          │
//            │                      │ 4. Получить дерево элементов             │
//            │                      │<─────────────────────────────────────────│
//            │                      │    [Сырой XML страницы iOS]              │
//            │                      │                                          │
//            │                      │ 5. ЛОКАЛЬНЫЙ СЖИМАТЕЛЬ (Java)            │
//            │                      │    XML (300 KB) ──> JSON (2 KB)          │
//            │                      │                                          │
//            │                      │ 6. Подбирает @iOSXCUITFindBy             │
//            │                      │    (самостоятельно, дешево)              │
//            │                      │                                          │
//            │<─────────────────────│                                          │
//            │ 7. "Все локаторы     │                                          │
//            │     собраны!"        │                                          │
//            │                      │                                          │
//            │ 8. Валидация /       │                                          │
//            │    Финальный код     │                                          │
//            ▼                      ▼                                          ▼
//
//
//
//    public class AppiumTools {
//
//        @Tool("Получить сжатый список элементов текущего экрана iOS")
//        public String getScreenElements() {
//            return XmlSanitizer.sanitizeIosXml(iosMcp.getPageSource());
//        }
//
//        @Tool("Сделать свайп/скролл вниз на симуляторе iOS, если элемент не виден")
//        public void scrollDown() {
//            iosMcp.execute("mobile: scroll", Map.of("direction", "down"));
//        }
//
//        @Tool("Кликнуть по элементу на экране симулятора iOS")
//        public void clickElement(String accessibilityId) {
//            iosMcp.click(accessibilityId);
//        }
}