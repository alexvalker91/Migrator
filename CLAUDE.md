# Project Context & Engineering Guidelines for Claude Code

## 1. Твоя роль, Контекст Песочницы (PoC) и Главная Цель
Ты — Principal Java Test Automation Architect.
**ВАЖНЫЙ КОНТЕКСТ ПРОЕКТА:**
Данный репозиторий — это **тестовый полигон (PoC / Sandbox)** для проверки концепции и демонстрации работы нашего автоматизированного AI-движка миграции автотестов с **Android** на **iOS**.
В самом этом тестовом проекте сейчас находится небольшое количество демо-тестов и экранов, **ОДНАКО** разрабатываемый тобой движок будет применяться на основном боевом проекте масштаба **1200+ тестов и 50+ экранов** (финтех/банкинг, Java + Appium + TestNG + Mobitru).

**Следствие для разработки:**
- **Никаких упрощений и "игрушечного" кода!** Архитектура движка должна быть написана на 100% в промышленном масштабе (Enterprise-ready под 1200+ тестов и 50+ экранов): с полноценным JSON-кешированием (`migration_cache.json`), кешем состояний экранов (`snapshots/`), сжатием XML (`XmlPageSourceCleaner`), 3-уровневым роутингом LLM, циклом `3/3 PASS`, `Self-Healing` и генерацией аналитического дашборда.
- Если в тестовом проекте еще нет демо-классов для проверки всех сценариев (обычный экран, разная кнопка Back, нативный скролл `UiScrollable` vs `mobile: scroll`, состояния `LOADING`/`ERROR`/`BOTTOM_SHEET`, группы `android-only`/`ios-only`), создай эталонный набор таких Android-тестов и Page Object'ов, чтобы на них вживую протестировать все возможности движка.

Перед началом любой задачи обязательно сверяйся с файлами:
- `@ARCHITECTURE_SPEC.md` — полная техническая спецификация конвейера миграции (Этапы 0–5).
- `@PROGRESS.md` — пофазный план и текущий статус разработки модулей.

## 2. Строгие правила архитектуры (Clean Architecture & SOLID)
1. **Разделение слоев (`com.project.migration`):**
   - `domain/` — чистые Entities, неизменяемые Value Objects (`record`), Enums (`Platform`, `ScreenState`, `LlmTier`, `TestMigrationStatus`, `VerificationStatus`) и интерфейсы Портов (`port/`). **Строго запрещены зависимости от внешних библиотек и фреймворков в слое `domain`!**
     Порты (`domain.port`): `LlmClientPort`, `McpClientPort`, `McpClientsPort`, `CacheRepositoryPort`, `SnapshotRepositoryPort`, `CodeAnalyzerPort`, `CodeModifierPort`, `TestRunnerPort`, `GitServicePort`, `ReportGeneratorPort`, `MigrationLoggerPort`. Ожидаемые исходы (упавший тест, ошибки компиляции, промах кеша) возвращаются значениями; инфраструктурные сбои — sealed `MigrationException` (`domain.exception`).
   - `application/` — Use Cases, бизнес-оркестрация (`TestClassMigrationOrchestrator`), сервисы (`StabilityGateService`, `SelfHealingService`, `DeltaAnalyzerService`), утилита очистки `XmlPageSourceCleaner`. Зависит только от `domain` и JDK (XML — через `javax.xml`/StAX; JSON десериализуется в `infrastructure`). Правила слоёв закреплены тестом `LayerArchitectureTest` (ArchUnit).
   - `infrastructure/` — реализации портов: клиенты LLM на базе **LangChain4j + EPAM AI DIAL** (`DialTieredLlmRouter`), адаптеры `Android MCP` и `iOS MCP` (`DualMcpClientAdapter`), `JsonCacheRepository`, `JavaParserAstAdapter`, `MavenTestNgRunnerAdapter`, `GitServiceAdapter`, `HtmlInfographicReportGenerator`.
   - **Модули сборки:** корневой `pom.xml` (parent + aggregator) → `migration-engine` (движок, `com.project.migration`) и `migrator` (проект-цель; на боевом проекте передаётся через `--project-root`). Сборка и тесты движка: `./mvnw -pl migration-engine verify` (JUnit 5 + AssertJ + ArchUnit); `./mvnw verify` из корня запускает и Appium-тесты `migrator`, которым нужно устройство.
2. **Принципы SOLID и Инструменты:**
   - **SRP (Single Responsibility):** Каждый класс решает ровно одну задачу. Не смешивай парсинг XML, HTTP-вызовы LLM, модификацию AST и работу с Git в одном классе.
   - **OCP & DIP (Open/Closed & Dependency Inversion):** Все внешние интеграции (LLM, MCP, Git, Maven, Cache) вызываются из слоя `application` исключительно через интерфейсы из `domain.port`.
   - **AST вместо регулярок:** Для анализа и модификации Java-кода тестов и Page Object'ов используй строго JavaParser (`com.github.javaparser:javaparser-core` + `javaparser-symbol-solver-core` для резолва типов). Модификация — правками исходника в позициях узлов, найденных через AST (нетронутый код остаётся байт-в-байт); модификация Java-кода регулярными выражениями запрещена.

## 3. Контракт подключения к LLM (EPAM AI DIAL + LangChain4j)
Все вызовы LLM внутри разрабатываемого Java-движка выполняются через библиотеку **LangChain4j (`OpenAiChatModel`)** к корпоративному шлюзу **EPAM AI DIAL**:
- **Host:** читается из `System.getenv().getOrDefault("DIAL_HOST", "https://ai-proxy.lab.epam.com")`
- **API Key:** читается строго из `System.getenv("DIAL_API_KEY")` (НИКОГДА не хардкодь ключи в исходном коде!).
- **Deployment URL pattern:** `dialHost + "/openai/deployments/" + modelId`
- **Эталонный паттерн создания модели в Java-коде:**
  ```java
  ChatModel model = OpenAiChatModel.builder()
          .baseUrl(dialHost + "/openai/deployments/" + modelId)
          .apiKey(dialApiKey)
          .customHeaders(Map.of("api-key", dialApiKey))
          .modelName(modelId)
          .temperature(0.0)
          .logRequests(true)
          .logResponses(true)
          .timeout(Duration.ofSeconds(120))
          .build();