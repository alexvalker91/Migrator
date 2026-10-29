# План и Прогресс Разработки Движка Миграции (6 Фаз)

Разработка ведется строго по очереди: 1 Фаза = 1 Сессия -> Компиляция и Unit-тесты -> Отметка `[x]` -> `/clear`.

- [x] **Фаза 1: Domain Layer, Ports & Logging (`showReasoning`)**
  - Мультимодульная сборка: корневой `pom.xml`, модули `migration-engine` (`com.project.migration`) и `migrator` (проект-цель); Maven Wrapper `./mvnw`.
  - Структура пакетов Clean Architecture (`domain`, `application`, `infrastructure`); правила слоёв закреплены `LayerArchitectureTest` (ArchUnit).
  - Доменные перечисления (`Platform`, `ScreenState`, `LlmTier`, `TestMigrationStatus`, `VerificationStatus`, `MigrationStage`, `TestOutcome`, `ComplexityMarkerType`, `PlatformScope`, `IosLocatorStrategy`, `HealingOutcome`, `SavingsSource`).
  - Неизменяемые модели (`TokenUsage`, `ModelPricing`, `LlmCallRecord`, `TelemetrySnapshot`, `CleaningResult`, `ScreenStateKey`, `StateSnapshot`, `ScreenElementMapping`, `ScreenCacheEntry`, `MethodRef`/`FieldRef`/`TestMethodRef`, sealed `CodeChange`, `MigrationTaskDelta`, `TestMethodTask`, `LlmRequest`/`LlmResponse` с sealed `LlmMessage`, `TestRunResult`, `CompilationResult`, `TestMethodReport`, `ClassMigrationReport`, `EngineConfig`, `CommitId`).
  - Sealed `MigrationException` и 6 подтипов (`domain.exception`).
  - Интерфейсы портов в `domain.port`: `LlmClientPort`, `McpClientPort`, `McpClientsPort`, `CacheRepositoryPort`, `SnapshotRepositoryPort`, `CodeAnalyzerPort`, `CodeModifierPort`, `TestRunnerPort`, `GitServicePort`, `ReportGeneratorPort`, `MigrationLoggerPort`.
  - Структурный логгер `LogbackMigrationLogger` (`migration_reports/<Class>/execution.log`): reasoning пишется в файл всегда, блоки `[🧠 LLM REASONING]` выводятся в консоль только при `showReasoning`; флаг `LlmRequest.reasoningRequested` выбирает `-reasoning`-модель.

- [x] **Фаза 2: Фундамент Фреймворка (`XmlPageSourceCleaner`, `BasePage`, `BaseTest`, `JavaParserAstAdapter`)**
  - Полная реализация `XmlPageSourceCleaner` (сжатие в 15–20 раз, белый список атрибутов, метка `offscreen="true"`, семантические зоны `TOP_APP_BAR`/`BOTTOM_NAV_BAR`/`BOTTOM_SHEET`/`scrollable_container`, схлопывание пустых оберток, маскирование секретов) + Unit-тесты.
  - Корневой `./mvnw verify` без девайса — зелёный: Appium-тесты `migrator` по умолчанию выключены (`skipTests=true` в `migrator/pom.xml` или профиль `e2e`), включаются явно (`-Pe2e` / `-DskipTests=false`); тот же флаг использует будущий `MavenTestNgRunnerAdapter`.
  - Кроссплатформенный `BaseTest` (Mobitru драйверы, быстрый рестарт `terminateApp`/`activateApp`) и `BasePage` (нативные скроллы, `waitForLoadingToDisappear`, `navigateBack`, `safeHideKeyboard`, `dismissBottomSheetBySwipe`, `openSystemNotifications`, `getCleanText`).
  - Реализация `JavaParserAstAdapter` для чтения и безопасной модификации AST тестовых классов и Page Object'ов.
  - Итог: `application.pagesource` (`XmlPageSourceCleaner` + профили Android/iOS, `SecretMasker`, `TokenEstimator`; сверх спеки без потерь: `resource-id` без префикса пакета приложения, iOS `label`/`value` без дублей, строки списков не схлопываются; синтетические банковские дампы: Android 15.8x, iOS 6.7x). `migrator`: `MobilePlatform`, `DriverFactory` (локальный Appium / Mobitru через `MOBITRU_AUTH_TOKEN` + `mobitru.*`/`<platform>.caps.*`), `BasePage` → `PlatformActions` (`AndroidActions`/`IosActions`), `BaseTest` (`-Dplatform` → suite-параметр), `ios_e2e_suite.xml`. Движок: `FrameworkContract`, `EngineConfig.sourceRoots`/`framework`, `ComplexityMarkerType.INLINE_LOCATOR`, `infrastructure.ast` (symbol solver + фоллбэк по имени/арности; модификация — правки исходника в AST-позициях вместо LexicalPreservingPrinter, который ломал отступы). Фикстура `fixtures/bank-project`.

- [ ] **Фаза 3: Кеш Состояний и `DialTieredLlmRouter` (LangChain4j + EPAM AI DIAL) + Телеметрия**
  - Реализация `JsonCacheRepository` (шардированный реестр `.migration/` с индексом `migration_cache.json`, атомарная запись) и `FileSnapshotRepository` (`.migration/snapshots/<ScreenName>/state_<STATE>[_<variant>]_<platform>_clean.xml` + PNG).
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