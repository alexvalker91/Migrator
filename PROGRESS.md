# План и Прогресс Разработки Движка Миграции (6 Фаз)

Разработка ведется строго по очереди: 1 Фаза = 1 Сессия -> Компиляция и Unit-тесты -> Отметка `[x]` -> `/clear`.

- [ ] **Фаза 1: Domain Layer, Ports & Logging (`showReasoning`)**
  - Создание структуры пакетов Clean Architecture (`domain`, `application`, `infrastructure`).
  - Доменные перечисления (`Platform`, `ScreenState`, `LlmTier`, `TestMigrationStatus`).
  - Неизменяемые модели (`TokenUsage`, `CleaningResult`, `ScreenElementMapping`, `MigrationTaskDelta`, `TestMethodReport`, `ClassMigrationReport`).
  - Интерфейсы портов в `domain.port`: `LlmClientPort`, `McpClientPort`, `CacheRepositoryPort`, `AstAnalyzerPort`, `TestRunnerPort`, `GitServicePort`, `ReportGeneratorPort`.
  - Структурный логгер (`MigrationLogger`) с поддержкой флага `--show-reasoning` и форматированным выводом блоков `[🧠 LLM REASONING]`.

- [ ] **Фаза 2: Фундамент Фреймворка (`XmlPageSourceCleaner`, `BasePage`, `BaseTest`, `JavaParserAstAdapter`)**
  - Полная реализация `XmlPageSourceCleaner` (сжатие в 15–20 раз, белый список атрибутов, метка `offscreen="true"`, семантические зоны `TOP_APP_BAR`/`BOTTOM_NAV_BAR`/`BOTTOM_SHEET`/`scrollable_container`, схлопывание пустых оберток, маскирование секретов) + Unit-тесты.
  - Кроссплатформенный `BaseTest` (Mobitru драйверы, быстрый рестарт `terminateApp`/`activateApp`) и `BasePage` (нативные скроллы, `waitForLoadingToDisappear`, `navigateBack`, `safeHideKeyboard`, `dismissBottomSheetBySwipe`, `openSystemNotifications`, `getCleanText`).
  - Реализация `JavaParserAstAdapter` для чтения и безопасной модификации AST тестовых классов и Page Object'ов.

- [ ] **Фаза 3: Кеш Состояний и `DialTieredLlmRouter` (LangChain4j + EPAM AI DIAL) + Телеметрия**
  - Реализация `JsonCacheRepository` (работа с `.migration/migration_cache.json` и папкой состояний `.migration/snapshots/<ScreenName>/state_<state>_clean.xml`).
  - Реализация `DialTieredLlmRouter` на базе `OpenAiChatModel` (LangChain4j) для работы с `https://ai-proxy.lab.epam.com/openai/deployments/{modelId}`, заголовком `api-key`, поддержкой мультимодальности (`ImageContent` для скриншотов) и переключением на `-reasoning` / `-with-thinking` модели при `showReasoning=true`.
  - Реализация `TelemetryCollector` (автоматический сбор токенов из ответов LangChain4j, расчет стоимости в `$`, подсчет сэкономленных токенов от очистки XML и кеша, замер времени каждого этапа).

- [ ] **Фаза 4: Dual-MCP Адаптеры и Сервисы Генерации Кода**
  - Реализация `DualMcpClientAdapter` для синхронной работы с Android MCP и iOS MCP, прохождения сценария и снятия дампов и скриншотов нужных состояний экранов (`ScreenState`).
  - Реализация `DeltaAnalyzerService` (Этап 1 — Tier-3 LLM).
  - Реализация `CodeAdaptationService` (Этап 3 — маппинг простых локаторов через Tier-2 LLM и адаптация сложной логики, нативных скроллов, шторок и групп через Tier-1 LLM).

- [ ] **Фаза 5: Главный Оркестратор, Фильтр «3 из 3 PASS», Git-чекпоинты и `SelfHealingService`**
  - Реализация `MavenTestNgRunnerAdapter` (запуск компиляции, поштучный запуск `@Test` методов на iOS/Android и парсинг `testng-results.xml`).
  - Реализация `StabilityGateService` (цикл 3 успешных прогонов подряд на iOS + фиксация промежуточного Git-чекпоинта после каждого `3/3 PASS`).
  - Реализация `SelfHealingService` (до 3 попыток лечения упавшего теста через Tier-1 Vision LLM + MCP, проверка выгорания тестовых данных, откат к Git-чекпоинту и изоляция в группу `ios-needs-fix` при неудаче).
  - Сборка главного конвейера `TestClassMigrationOrchestrator` (Этапы 0–5, включая финальный `AndroidRegressionGuard`).

- [ ] **Фаза 6: Git-адаптер, Генератор HTML-Инфографики (`dashboard.html`) и CLI Runner**
  - Реализация `GitServiceAdapter` (создание ветки `migrate/ios-<TestClass>`, промежуточные чекпоинты, откат неудачных правок лечения, финальный коммит и пуш).
  - Реализация `HtmlInfographicReportGenerator` (генерация `dashboard.html` с Chart.js и `report.md`: графики токенов/стоимости/экономии/таймингов, таблица тестов со скриншотами падений, вердиктами и спойлерами `[🧠 LLM REASONING]`).
  - Создание CLI/Maven точки входа (`MigrationCliRunner`) для запуска миграции любого тестового класса.