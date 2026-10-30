# Полная экскурсия по проекту Migrator

Этот документ написан по реальному коду на диске (состояние после Фазы 6: 269 классов в `migration-engine`, 62 тестовых
класса, 353 теста, 41 коммит). Рядом лежат два файла:

- `docs/architecture.drawio` — две схемы (откройте на https://app.diagrams.net/): страница «Слои и Классы» и страница
  «Конвейер Этапов 0-5»;
- `docs/demo_dashboard.html` — пример итогового дашборда на выдуманных, но реалистичных данных.

---

## 1. Что это такое, простыми словами

Есть большой набор автотестов для мобильного банковского приложения, написанный под **Android** (Java + Appium + TestNG).
Нужно, чтобы те же тесты работали и на **iOS**. Руками это месяцы работы: у каждого элемента на экране должен появиться
iOS-локатор, а всё, что завязано на Android (нативный скролл `UiScrollable`, системная кнопка «Назад», скрытие клавиатуры),
надо переписать.

**Migrator** делает это автоматически, класс за классом. Вы говорите: «мигрируй `CardsTest`», и движок:

1. читает код теста и понимает, какие экраны и шаги в нём участвуют;
2. проходит эти экраны на живых Android и iOS через Appium MCP и запоминает, как они выглядят (очищенные XML и скриншоты);
3. просит LLM сопоставить элементы Android и iOS и **сам проверяет** каждое предложение по снятому XML;
4. правит Java-код **через AST** (не регулярками), так что нетронутые строки остаются байт-в-байт теми же;
5. запускает каждый тест на iOS до трёх зелёных прогонов подряд; если тест падает, зовёт «самоизлечение» (LLM смотрит на
   скриншот, XML и стектрейс и предлагает правку);
6. всё удачное фиксирует git-коммитом, всё безнадёжное откатывает и помечает группой `ios-needs-fix`;
7. строит дашборд с деньгами, временем, результатами и прогнозом на оставшиеся 1200+ тестов.

Три идеи, на которых всё держится:

- **LLM предлагает, код проверяет.** Любой ответ модели (локатор, новое тело метода, правка после падения) проходит
  детерминированную проверку, прежде чем попасть на диск.
- **Знания копятся.** Всё, что подтвердили три зелёных прогона, записывается в кеш и больше не тратит токены. Поэтому каждый
  следующий класс дешевле предыдущего (это видно на графике «кривая обучения» в дашборде).
- **Ничего необратимого.** Движок работает только в ветке `migrate/ios-<Class>`, откатывает файлы поштучно, не делает
  `force push`, не стирает чужие незакоммиченные правки (на грязном дереве он просто не стартует).

---

## 2. Карта репозитория

```
Migrator/
├── pom.xml                 родитель и агрегатор сборки
├── migration-engine/       сам движок (пакет com.project.migration)
│   └── src/main/java/…     domain / application / infrastructure
│   └── src/main/resources  engine-defaults.json, prompts/*.md (18 промптов), report/ (шаблон, Chart.js, CSS)
│   └── src/test/java/…     тесты, testsupport (фейки), tools/DemoDashboardMain
├── migrator/               песочница: целевой проект (Page Object'ы, BaseTest, LoginTest)
├── docs/                   этот гид, architecture.drawio, demo_dashboard.html
├── ARCHITECTURE_SPEC.md    полная спецификация конвейера (Этапы 0-5)
├── PROGRESS.md             пофазный план и статус (все 6 фаз отмечены)
├── CLAUDE.md               инженерные правила проекта
└── .mcp.json               как Claude Code видит те же два Appium MCP
```

Сборка и тесты: `./mvnw -pl migration-engine verify` (без устройства). `./mvnw verify` из корня тоже зелёный: Appium-тесты
`migrator` по умолчанию пропускаются (`skipTests=true`, включаются профилем `-Pe2e`).

---

## 3. Архитектура: четыре слоя и одно правило

Правило: **зависимости направлены только внутрь**. Это не договорённость, а тест: `LayerArchitectureTest` (ArchUnit)
падает, если `domain` обратится к внешней библиотеке, `application` к `infrastructure`, а порт будет не интерфейсом.

| Слой | Пакет | Что внутри | Что ему можно |
|---|---|---|---|
| Domain | `domain.model`, `domain.exception` | неизменяемые данные (`record`), перечисления | только JDK |
| Ports | `domain.port` | 15 интерфейсов `*Port` | только JDK и domain |
| Application | `application.*` | сценарии: Этапы 0-5, агентный цикл, самоизлечение, отчётная логика | domain и JDK (XML через `javax.xml`) |
| Infrastructure | `infrastructure.*` | адаптеры портов: LangChain4j, MCP SDK, JavaParser, Jackson, git, Maven | всё, но реализует порты |

Дополнительные приёмы, которые встретятся в коде:

- **Ожидаемое возвращается значением, исключения только для поломок.** Упавший тест, ошибка компиляции, недостижимое
  состояние экрана, отклонённое предложение LLM, статус `ABORTED` — это данные (`TestRunResult`, `CompilationResult`,
  `UnreachableState`, `MigrationExitStatus`). Исключение `MigrationException` (запечатанный класс из 6 подтипов) означает
  «сломалась инфраструктура»: LLM-шлюз недоступен, MCP упал, git отказал.
- **Интерфейсы этапов.** Оркестратор знает не классы, а интерфейсы `DeltaAnalyzer`, `SnapshotCollector`, `CodeAdapter`,
  `TestStabilizer`, `Healer`, `RegressionGuard`, `ComponentInitializer`, `CompilationRepairer`, `ClassMigrator`. Поэтому
  любой этап подменяется в тесте одной лямбдой.
- **Детерминизм там, где можно, LLM там, где нужно.** AST-разбор, вычитание кеша, очистка XML, проверка локаторов,
  проверка тела метода, откаты, статусы, математика проекции — обычный код без токенов.

---

## 4. Экскурсия по слоям и пакетам

В колонке «Ф» указана фаза, в которой пакет или класс появился (Ф1 каркас; Ф2 фундамент и AST; Ф3 кеш, LLM, телеметрия;
Ф4 MCP, агентный цикл, Этапы 1-3; Ф5 Maven, самоизлечение, шлюз 3/3, оркестратор; Ф6 git, отчёт, CLI).

### 4.1 Domain (`domain.model`, `domain.exception`)

Здесь нет логики, только «что мы знаем о мире». Почти всё — `record` с проверкой в конструкторе, поэтому некорректное
значение просто нельзя создать.

**Корень `domain.model`** (Ф1): `Checks` — общие проверки аргументов; `MigrationStage` — этапы (`STAGE_0_GLOBALS` …
`STAGE_5_FINALIZE`, а также `STAGE_4_SELF_HEALING`, добавленный в Ф5, чтобы отделить время самоизлечения от времени
прогонов); `Platform` — `ANDROID`/`IOS` и их токены для имён файлов и `-Dplatform`.

**`domain.model.code`** — язык правок кода.

| Класс | Смысл |
|---|---|
| `CodeChange` (sealed) | любая AST-правка; его наследники ниже |
| `AddIosLocator` | добавить `@iOSXCUITFindBy` над `@AndroidFindBy` поля |
| `ReplaceMethodBody` | заменить тело метода |
| `InsertCallBefore` | вставить оператор (например `scrollToElementByText`) перед заданным |
| `AddTestGroups` | добавить группы `android-only` / `ios-only` / `ios-needs-fix` |
| `AddImport` | добавить импорт |
| `MethodRef`, `FieldRef`, `TestMethodRef` | устойчивые ссылки на метод/поле/тест; служат ключами кеша |
| `LocatorField` | поле-локатор: Android-форма, есть ли iOS, список ли элементов (Ф4) |
| `IosLocatorStrategy` | `accessibility` → `iOSNsPredicate` → `iOSClassChain` (в порядке приоритета) |
| `FrameworkContract` | договор с фреймворком проекта: базовые классы, имена 8 хелперов `BasePage`, классы-«платформенные фичи» (GooglePay) |
| `BodyInspection` | вердикт о кандидате на новое тело метода: синтаксис, остатки Android-кода, несуществующие вызовы (Ф4) |
| `ModificationResult`, `CodeNames` | итог применения правок; утилита имён классов |

**`domain.model.analysis`** (Ф1, Ф4) — результат Этапа 1: `MigrationTaskDelta` (что осталось сделать после вычитания кеша:
задачи, недостающие слепки, локаторы, шаги, экран каждого класса), `TestMethodTask` (один тест: дерево вызовов, нужные
состояния экранов, маркеры, область), `PlatformScope` (`BOTH` / `ANDROID_ONLY` / `IOS_ONLY`), `ComplexityMarker` и
`ComplexityMarkerType` (`UI_SCROLLABLE`, `ANDROID_KEY_BACK`, `OPEN_NOTIFICATIONS`, `HIDE_KEYBOARD`,
`PLATFORM_SPECIFIC_FEATURE`, `ANDROID_ONLY_API`, `INLINE_LOCATOR`).

**`domain.model.screen`** (Ф1) — `ScreenId` (имя Page Object'а, безопасное для файловой системы), `ScreenState`
(`DEFAULT`, `LOADING`, `EMPTY`, `VALIDATION_ERROR`, `BOTTOM_SHEET`, `MODAL_DIALOG`, `SUCCESS`), `ScreenStateKey`
(экран + состояние + вариант, из него строится имя файла слепка), `StateSnapshot` (очищенный XML + скриншот),
`CleaningResult` (результат очистки XML и сколько токенов сэкономлено), `ComponentId`, `SafeName`.

**`domain.model.cache`** (Ф1) — что помнит база знаний: `ScreenCacheEntry` (состояния экрана и сопоставленные локаторы),
`ScreenElementMapping` (Android-локатор → iOS-локатор + статус), `StepCacheEntry` (метод Page Object'а, прошедший 3/3),
`TestCacheEntry` (статус теста), `GlobalComponentEntry` (сквозной компонент), `IosLearnedRule` (особенность iOS-приложения,
найденная по ходу работы), `VerificationStatus` (`UNVERIFIED` → `VERIFIED_STABLE` либо `FAILED`).

**`domain.model.llm`** (Ф1, Ф3) — нейтральный к провайдеру диалог с моделью: `LlmRequest`/`LlmResponse`, сообщения
(`SystemMessage`, `UserMessage` со скриншотами, `AssistantMessage`, `ToolResultMessage`), `ToolSpec`/`ToolCall`, `LlmTier`
(Tier-1 vision+reasoning, Tier-2 средняя, Tier-3 дешёвая), `TierModelConfig` (какие деплойменты DIAL обслуживают ярус и
цепочка запасных).

**`domain.model.mcp`**, **`media`** (Ф1, Ф4) — `McpToolResult`, `RawPageSource` (сырой XML, его нельзя класть в кеш или промпт
без очистки), `McpToolCall`, `ImageAttachment`.

**`domain.model.agent`**, **`snapshot`**, **`codegen`** (Ф4) — итоги Этапов 2 и 3: `AndroidWalkthrough` (типизированная запись
Android-прохода: шаги и тексты экранов), `AgentRunOutcome` (`COMPLETED` / `BUDGET_EXHAUSTED` / `STUCK`),
`SnapshotCollectionResult` и `UnreachableState` с причиной (`UnreachableReason`), `CodeAdaptationResult`, `MappedLocator`,
`UnresolvedLocator`, `LocatorConflict`, `ManualAdaptation`.

**`domain.model.run`** (Ф1) — результаты Maven/TestNG: `TestRunResult` (исход `PASSED`/`FAILED`/`SKIPPED`/`ERROR`, пути к
скриншоту и XML падения), `TestClassRunResult`, `CompilationResult`/`CompilationError`, `TestFailure`, `TestOutcome`.
Лечится только `FAILED`; `SKIPPED` и `ERROR` считаются проблемой окружения.

**`domain.model.report`** (Ф1, Ф6) — всё для отчёта: `ClassMigrationReport` (включая `exitStatus` и
`androidRegressions`), `TestMethodReport`, `TestMigrationStatus` (`STABLE_PASS`, `HEALED_AND_PASSED`, `NEEDS_MANUAL_FIX`,
`PLATFORM_SPECIFIC`, `SKIPPED_ALREADY_VERIFIED`, …), `HealingAttempt`/`HealingOutcome`, `FailureEvidence`, `ProjectProgress`
(«187 из 1200 тестов, 14 из 52 экранов»), `ReportArtifacts`.

**`domain.model.orchestration`** (Ф5) — `TestClassMigrationRequest` (класс, `push`, `pushOnRegression`),
`ClassMigrationOutcome`, `MigrationExitStatus` (0 `SUCCESS`, 1 `COMPLETED_WITH_MANUAL_FIX`, 2 `STAGE5_FAILED`, 3 `ABORTED`).

**`domain.model.config`** (Ф1, Ф4, Ф5) — `EngineConfig` (всё настраиваемое одним объектом), `McpServerConfig`,
`AgentLoopConfig` (лимиты агента и белый список MCP-инструментов), `MavenConfig`, `GateConfig` (бюджеты на тест).

**`domain.model.telemetry`** (Ф1, Ф3, Ф5) — `TokenUsage`, `LlmCallRecord`, `ModelPricing`, `TelemetrySnapshot` (деньги по
ярусам, экономия, время по этапам), `TestTelemetry` (то же по одному тесту), `CacheStatistics`, `SavingsSource`
(`XML_CLEANING`, `SNAPSHOT_CACHE`, `MIGRATION_CACHE`), `TokenSavings`.

**`domain.model.history`** (Ф6) — `ClassRunSummary` (одна строка истории: сколько стоил класс) и `ScalingProjection`
(три сценария, время на 1/2/4 устройствах, кривая обучения).

**`domain.model.vcs`** (Ф1, Ф5) — `BranchNames` (префикс `migrate/ios-`, тексты коммитов `codegen:`/`checkpoint:`/`isolate:`),
`CommitId`.

**`domain.exception`** (Ф1) — `MigrationException` и подтипы `LlmCallException`, `McpException`, `CacheCorruptedException`,
`GitOperationException`, `TestRunnerException`, `CodeModificationException`.

### 4.2 Ports (`domain.port`): 15 «розеток»

Порт — это интерфейс, через который сценарии говорят с внешним миром. За каждым стоит ровно один адаптер.

| Порт | Что делает | Кто реализует (Ф) |
|---|---|---|
| `LlmClientPort` | один ход диалога с моделью нужного яруса | `DialTieredLlmRouter` (Ф3) |
| `McpClientsPort` / `McpClientPort` | сессии Android и iOS MCP: инструменты, page source, скриншот, рестарт приложения | `DualMcpClientAdapter` (Ф4) |
| `CodeAnalyzerPort` | чтение кода по AST: тесты, дерево вызовов, локаторы, маркеры, проверка тела метода | `JavaParserAstAdapter` (Ф2, расширен в Ф4-5) |
| `CodeModifierPort` | атомарное применение пакета правок | `JavaParserCodeModifier` (Ф2) |
| `TestRunnerPort` | `compile()`, прогон одного теста, прогон класса | `MavenTestNgRunnerAdapter` (Ф5) |
| `GitServicePort` | ветка, коммиты, откат файлов, push | `GitServiceAdapter` (Ф6) |
| `CacheRepositoryPort` | база знаний `.migration/` | `JsonCacheRepository` (Ф3) |
| `SnapshotRepositoryPort` | очищенные XML и скриншоты состояний | `FileSnapshotRepository` (Ф3) |
| `ReportGeneratorPort` | `dashboard.html` и `report.md` | `HtmlInfographicReportGenerator` (Ф6) |
| `WalkthroughSinkPort` | сохранение Android-прохода | `JsonWalkthroughSink` (Ф4) |
| `RunHistoryPort` | история запусков для проекции | `JsonlRunHistory` (Ф6) |
| `TelemetrySinkPort` | журнал LLM-вызовов `llm_calls.jsonl` | `JsonlTelemetrySink` (Ф3) |
| `MigrationLoggerPort` | журнал сессии и блоки рассуждений | `LogbackMigrationLogger` (Ф1) |
| `ArtifactStorePort` | чтение и копирование улик падения | `FileArtifactStore` (Ф5) |

### 4.3 Application: сценарии использования

Каждый пакет — один этап или одна вспомогательная способность.

#### `application.pagesource` (Ф2): сжатие XML без токенов
`XmlPageSourceCleaner` превращает сырой `pageSource` (десятки тысяч токенов) в компактный XML в 7-16 раз меньше.
Оставляет только нужные атрибуты (`name`, `label`, `value`, `enabled` для iOS; `resource-id`, `text`, `content-desc`,
`class` для Android), вешает метки зон (`TOP_APP_BAR`, `BOTTOM_NAV_BAR`, `BOTTOM_SHEET`), помечает элементы вне экрана
`offscreen="true"` (сигнал к скроллу), схлопывает пустые обёртки, сокращает теги и маскирует токены `Bearer ***`.
Вспомогательные: `AndroidCleaningProfile`/`IosCleaningProfile` (правила платформ через общий `CleaningProfile`),
`CleanNode`, `CleanXmlWriter`, `SecretMasker`, `SemanticZone`, `TokenEstimator`.

#### `application.agent` (Ф4): цикл «модель ↔ MCP»
- `AgentLoop` — раунды вызовов инструментов с лимитами (30 шагов, 8 без прогресса, 150k токенов); исход — значение
  (`COMPLETED` / `BUDGET_EXHAUSTED` / `STUCK`). В диалоге остаются только два последних XML экрана.
- `AgentToolbox` — какие инструменты видит модель: белый список MCP (список настраивается), плюс инструменты самого движка.
  Страницу всегда отдаёт через очиститель, длинные ответы режутся.
- `SyntheticTool`, `ToolOutcome`, `ToolTrace`, `AgentTask`, `AgentRunResult` — данные цикла; `FlatJson` — крошечный разбор
  плоского JSON-объекта (в слое нет JSON-библиотеки).

#### `application.delta` (Ф4): Этап 1
`DeltaAnalyzerService` (за интерфейсом `DeltaAnalyzer`): через `CodeAnalyzerPort` берёт тесты класса, дерево вызовов и
маркеры, вычитает из них всё, что уже `VERIFIED_STABLE` в кеше, и **один раз** спрашивает Tier-3, какому экрану соответствует
какой Page Object и какие состояния нужны каждому тесту. Ответ проверяет `DeltaAnalysisValidator` (неверное отбрасывается,
одна повторная просьба); `DeltaAnalysis` и `ValidatedAnalysis` — формы данных. Правило: каждому затронутому экрану нужно
как минимум состояние `DEFAULT`. Тесты с классами «платформенных фич» (GooglePay) получают область `ANDROID_ONLY`.

#### `application.snapshot` (Ф4): Этап 2
`SnapshotCollectionService` (интерфейс `SnapshotCollector`): на каждый тест — чистый рестарт обоих приложений, затем
Android-проход (агент Tier-2 ведёт приложение по коду теста и `@BeforeMethod`, учётные данные берёт только из кода), запись
`AndroidWalkthrough`, затем iOS-проход по этой записи. Слепок пишет единственный путь — инструмент
`capture_snapshot` (`SnapshotCaptureTool`): движок сам берёт page source и скриншот, очищает и сохраняет; модель XML не
видит и не переписывает. `WalkthroughSummary` — форма ответа модели при составлении записи.

#### `application.codegen` (Ф4): Этап 3
- `CodeAdaptationService` (`CodeAdapter`) — дирижёр: маппинг локаторов, переписывание сложных методов, вставка
  `scrollToElementByText` перед `offscreen`-элементами, группы `android-only`, затем применение пакета и запись в кеш как
  `UNVERIFIED`.
- `LocatorMapper` — на пару (экран, состояние) один вызов Tier-2; предложение проверяет `IosLocatorValidator` по XML iOS
  (локатор должен находить ровно один элемент; для списка — хотя бы один); отклонённое: одна повторная просьба Tier-2, затем
  Tier-1. Одно поле — один локатор; расхождения между состояниями экрана попадают в `LocatorConflict`.
  `AndroidLocatorMatcher` понимает простые Android-локаторы (`id`, `accessibility`, `text`, `className`) для этой проверки.
- `MethodAdapter` — Tier-1 с reasoning переписывает методы с `UiScrollable`, `AndroidKey.BACK`, клавиатурой, уведомлениями,
  встроенными локаторами; ответ принимается, только если `inspectBody` подтверждает: парсится, Android-остатков нет,
  вызываются лишь существующие методы и разрешённые хелперы `BasePage`.
- `ResilientApplier` — применяет пакет атомарно, а если какая-то правка отвергнута, пробует по одной.
- Данные: `XmlElement`, `MappingOutcome`, `AdaptationOutcome`, `LocatorMappingAnswer`, `MethodRewrite`.

#### `application.stability` (Ф5): Этап 4, шлюз «3 из 3»
`StabilityGateService` (`TestStabilizer`) — главный цикл по одному тесту: прогон на iOS; `PASSED` — серия растёт; `FAILED` —
зовётся самоизлечение и серия обнуляется; `SKIPPED`/`ERROR` — повтор без правок. Три подряд — успех: кеш помечается
`VERIFIED_STABLE`, коммит `checkpoint: <метод> passed 3/3 on iOS`. Неудача (попытки или бюджет кончились, виноват общий
класс): откат файлов к опорному коммиту, группа `ios-needs-fix`, коммит `isolate:`, улики копируются в
`migration_reports/<Class>/failures/<метод>/`. `VerificationRecorder` пишет результат в кеш (и не перетирает уже
`VERIFIED_STABLE` статусом `FAILED`). `GateRequest`, `GateOutcome`, `GateFailureKind` — данные.

#### `application.healing` (Ф5): самоизлечение и починка компиляции
`SelfHealingService` (`Healer`) — одна попытка: собрать досье (стектрейс, скриншот, очищенный XML экрана падения, исходники
теста и вызванных методов, слепки, известные правила iOS, прошлые попытки) → ограниченное исследование на живом iOS MCP
(Tier-1, до 10 шагов) → структурный ответ Tier-1 `HealingProposal` (корневая причина + правки) → `HealingProposalValidator`
→ применение → компиляция. Если не скомпилировалось, правки откатываются к опорному коммиту, а правки прошлых попыток
накатываются заново. Причины, не лечащиеся кодом (`TEST_DATA`, `SHARED_FRAMEWORK`, `ENVIRONMENT`), заканчивают лечение без
правок. Менять можно только классы дерева вызовов теста, никогда `BasePage` и `BaseTest`.
`CompilationRepairService` (`CompilationRepairer`) — то же для ошибок компиляции после Этапа 3 (Tier-1, два круга).
Данные: `HealingProposal`, `HealingRootCause`, `ChangeKind`, `HealingRequest`, `HealingResult`, `HealingStatus`, `CompileFix`.

#### `application.orchestrator` (Ф5): конвейер класса
`TestClassMigrationOrchestrator` (`ClassMigrator`) последовательно вызывает этапы (подробно в разделе 5).
`GlobalComponentInitializer` — Этап 0: сквозные компоненты из `global_components` ищутся на уже снятых слепках.
`AndroidRegressionGuard` — единственный прогон класса на Android; упавший метод перезапускается один, регрессия — только
если упал и второй раз. `ProjectProgressCalculator` — «N из 1200 тестов, M из 50 экранов».
Данные: `RegressionResult`, `ComponentInitResult`.

#### `application.report` (Ф6): мозги отчёта
`ProjectionCalculator` — проекция на оставшиеся тесты из записанной истории (формулы в разделе 7). `RunSummaryFactory` —
превращает отчёт в строку истории. `ReportNarrator`/`Tier3ReportNarrator` — один дешёвый вызов, пишущий резюме и
следующий шаг по каждому сломанному тесту; при любой неудаче работает `DeterministicNarrator` (текст из данных).
`ReportNarrative`, `NarrativeAnswer` — данные.

#### `application.telemetry` (Ф3, Ф5), `application.prompt` (Ф4)
`TelemetryCollector` — токены и деньги по ярусам, экономия, cache hit rate, время по этапам и по тестам.
`TelemetryRecordingLlmClient` — обёртка над `LlmClientPort`: записывает каждый вызов и выводит reasoning в журнал.
`PromptTemplates` — подставляет значения в 18 шаблонов `prompts/*.md` (строгая проверка: лишнее или недостающее значение
— ошибка).

### 4.4 Infrastructure: адаптеры

#### `infrastructure.cli` (Ф6) — вход и сборка
`MigrationCliRunner` (`main`, коды выхода), `CliArguments` (ручной разбор аргументов), `ClassResolver` (`CardsTest`,
`CardsTest.java` или полное имя; неоднозначность → ошибка), `ProjectLock` (`.migration/engine.lock`: один движок на проект),
`EngineAssembly` (**корень композиции**: единственное место, где создаются все адаптеры и сервисы и связываются), `Engine`,
`ConsoleSummary` (итоговая таблица), `UsageException` (код 64).

#### `infrastructure.llm` (Ф3) — EPAM AI DIAL через LangChain4j
`DialTieredLlmRouter` выбирает деплоймент по ярусу (и reasoning-вариант, если нужен), переключается на запасные только при
404/5xx/таймауте, просит строгий JSON по схеме (а если шлюз не умеет, кладёт схему в промпт и один раз просит исправить),
проверяет, что для скриншота выбрана vision-модель. Рядом: `DialChatModelFactory` (создаёт `OpenAiChatModel` по эталонному
паттерну), `DialSettings` (хост и `DIAL_API_KEY` только из окружения), `LlmMessageMapper`, `ToolSpecMapper`,
`StructuredOutput`, `JsonSchemaText`, `LlmFailures`, `LlmWireLog` (`-Dmigration.llm.wireLog=true` пишет «провод» в файл).

#### `infrastructure.mcp` (Ф4) — два Appium MCP
`DualMcpClientAdapter` лениво запускает по процессу `appium-mcp` на платформу через stdio (`StdioMcpClient`), выполняет
`startup_calls` (выбор устройства, создание сессии), ограничивает каждый вызов таймаутом. `McpEnvironment` подставляет
`${VAR}` из окружения, `McpResultMapper` переводит типы MCP SDK в доменные.

#### `infrastructure.ast` (Ф2, расширен в Ф4-6) — JavaParser
`JavaParserAstAdapter` (`CodeAnalyzerPort`) и `JavaParserCodeModifier` (`CodeModifierPort`) используют общий индекс исходников
`ProjectSourceIndex`. Читают: `TestMethodScanner`, `CallTreeResolver` + `CallTargetResolver` (дерево вызовов с резолвом типов),
`LocatorFieldFinder`, `ComplexityMarkerDetector`, `BodyInspector`, `SetupMethodFinder` (`@BeforeMethod`),
`ScreenClassFinder` (список экранов проекта). Пишут правками по позициям узлов: `AddIosLocatorApplier`,
`ReplaceMethodBodyApplier`, `InsertCallBeforeApplier`, `AddTestGroupsApplier` (общий интерфейс `ChangeApplier`), `SourceText`
(правки по смещениям), `Snippets` (разбор кусочков кода), `AtomicFileWriter` (все файлы или ни одного), `SourceLookup`,
`SourceRootLocator`.

#### `infrastructure.cache`, `snapshot` (Ф3)
`JsonCacheRepository` — шардированная база: индекс и по файлу на экран, класс шагов и класс тестов; запись атомарна
(временный файл + переименование) и защищена `DirectoryLock`; повреждённый файл вызывает `CacheCorruptedException`, а не
молча перезаписывается. `CacheJson`/`CacheJsonMapper` — формат на диске. `FileSnapshotRepository` — слепки по именам вида
`state_BOTTOM_SHEET_card_picker_ios_clean.xml`.

#### `infrastructure.maven` (Ф5)
`MavenTestNgRunnerAdapter` (`TestRunnerPort`): запускает Maven (`./mvnw` проекта или `mvn`), перед каждым прогоном удаляет
старый `testng-results.xml`, убивает процесс по таймауту (ошибка окружения, не провал теста). `MavenCommandBuilder` (в
прогоне класса сам передаёт `-DexcludedGroups`: на iOS `android-only,ios-needs-fix`, на Android `ios-only`),
`TestNgResultsParser` (результаты; упавший `@BeforeMethod` → `SKIPPED`), `CompilerOutputParser` (ошибки javac),
`FailureArtifactFinder` (скриншот и XML, сохранённые слушателем проекта).

#### `infrastructure.git` (Ф6)
`GitServiceAdapter` — системный `git` (хуки и подпись коммитов работают как обычно). Коммитит, откатывает и пушит только в
ветках `migrate/ios-*`; нет `reset --hard`, `clean`, `push --force`, удаления веток.

#### `infrastructure.report`, `history` (Ф4, Ф6)
`HtmlInfographicReportGenerator` собирает `dashboard.html` (шаблон `dashboard.html.tpl` + CSS CodeMie + Chart.js, всё
вшито) и `report.md`. `DashboardBodyRenderer` (HTML-таблицы и блоки), `ReportData` (JSON для графиков),
`MarkdownReportWriter`, `ScreenshotEmbedder` (уменьшает скриншоты до 640 px и вшивает, лимит 5 МБ), `HtmlEscaper`
(экранирование всего динамического текста), `ReportFormat`. `JsonWalkthroughSink` — запись Android-прохода.
`JsonlRunHistory` — `.migration/history/runs.jsonl`.

#### Мелкие, но нужные (`config`, `fs`, `json`, `logging`, `process`, `telemetry`)
`JsonEngineConfigLoader` (+ `engine-defaults.json` и необязательный `--config`, слияние по ключам), `AtomicFiles`,
`DirectoryLock`, `FileArtifactStore`, `JsonMappers`, `LogbackMigrationLogger` (`execution.log` + блоки `[🧠 LLM REASONING]`,
в консоль только при `--show-reasoning`), `CommandExecutor`/`ProcessCommandExecutor` (запуск процессов с таймаутом),
`JsonlTelemetrySink` (`llm_calls.jsonl`).

### 4.5 Модуль `migrator`: проект-песочница

Это «боевой проект в миниатюре», на котором проверяется движок.

| Что | Файлы | Роль |
|---|---|---|
| Page Object'ы | `page/BasePage`, `LoginPage`, `HomeScreen`, `BottomNavComponent` | `BasePage` содержит кросс-платформенные хелперы, на которые опирается кодогенерация: `waitForLoadingToDisappear`, `scrollToElementByText`, `scrollInsideContainer`, `navigateBack`, `safeHideKeyboard`, `dismissBottomSheetBySwipe`, `openSystemNotifications`, `getCleanText` |
| Платформенные действия | `page/actions/PlatformActions`, `AndroidActions`, `IosActions`, `W3cGestures`, `ScrollDirection` | как именно делается скролл/назад/клавиатура на каждой платформе |
| Ядро | `core/MobilePlatform`, `core/driver/DriverFactory`, `core/config/WebdriverioConfig`, `ConfigLoader` | создание сессии Appium: ферма Mobitru, если задан `MOBITRU_AUTH_TOKEN`, иначе локальный сервер; опции UiAutomator2 (Android) или XCUITest (iOS) |
| Тесты | `webdriverio/BaseTest`, `LoginTest` | `BaseTest` читает `-Dplatform`, перезапускает приложение перед каждым тестом, понимает группы |
| Улики падения | `listener/ScreenshotListener` | пишет `target/screenshots/<тест>_<время>.png` и `target/page-source/<тест>_<время>.xml` |
| Сьюты | `resources/webdriverio/android_e2e_suite.xml`, `ios_e2e_suite.xml` | фильтры групп; на iOS исключены `android-only` и `ios-needs-fix` |

Связь с движком: `MavenTestNgRunnerAdapter` запускает `mvn test -Pe2e -Dtest=Class#method -Dplatform=iOS`;
`JavaParserAstAdapter` читает и правит `.java`; `FailureArtifactFinder` подбирает файлы слушателя.

---

## 5. Как работает вся цепочка, шаг за шагом

Допустим, вы запускаете: `java -jar migration-engine-cli.jar CardsTest --project-root /work/bank-tests`.
(Тот же путь нарисован на странице 2 `architecture.drawio`.)

**0. Вход (`MigrationCliRunner`).**
Разбираются аргументы; грузится конфиг (`engine-defaults.json` плюс ваш `--config`); проверяются переменные окружения
(`DIAL_API_KEY` и все `${VAR}` из конфига); берётся `.migration/engine.lock`; `EngineAssembly` собирает все части;
`ClassResolver` превращает `CardsTest` в `com.bank.tests.CardsTest`. Любая ошибка тут — код 64 и ничего не тронуто.

**1. Подготовка (`TestClassMigrationOrchestrator`).**
Если в рабочем дереве есть незакоммиченные правки, движок печатает список файлов и выходит с кодом 3: он не умеет отличить
ваши правки от мусора. Иначе создаётся или подхватывается ветка `migrate/ios-CardsTest`, открываются журналы.

**2. Этап 1: дельта.**
AST: какие `@Test` есть, какие Page Object'ы и методы они вызывают, где `UiScrollable` и прочие маркеры. Кеш: что уже
подтверждено, то вычеркнуто (и уже смигрированные тесты пропущены целиком). Остаток нужно «разметить»: один дешёвый вызов
Tier-3 говорит, какой Page Object — какой экран и какие состояния (`LOADING`, `BOTTOM_SHEET`, …) нужны каждому тесту.
Если делать нечего, LLM не вызывается вообще.

**3. Этап 2: слепки через два MCP.**
Только для недостающих состояний. На каждый тест: рестарт обоих приложений; агент Tier-2 проходит сценарий на Android и в
нужные моменты вызывает `capture_snapshot`; движок сам снимает XML и скриншот, очищает и кладёт в `.migration/snapshots/`.
Из лога прохода Tier-2 собирает типизированную запись `AndroidWalkthrough`; iOS-агент повторяет её и снимает те же состояния.
Недостижимое состояние не падение: оно попадает в результат с причиной.

**4. Этап 0: глобальные компоненты** (только если в конфиге есть `global_components`).
Компоненты вроде нижней навигации ищутся на только что снятых слепках, получают iOS-локаторы и запись в кеше.

**5. Этап 3: кодогенерация.**
Для каждого экрана и состояния Tier-2 предлагает iOS-локаторы; движок проверяет каждый по XML iOS (ровно один элемент) и
просит исправить, если не прошло; потом эскалирует на Tier-1. Методы с Android-спецификой переписывает Tier-1, причём ответ
сначала проверяется `inspectBody`. Элементам с `offscreen="true"` вставляется скролл. Всё применяется пакетом через AST.
Затем `mvn test-compile`; если красно, `CompilationRepairService` чинит до двух раз, иначе всё откатывается и класс
завершается `ABORTED`. Зелёное фиксируется коммитом `codegen: CardsTest`: это точка, к которой можно откатиться.

**6. Этап 4: стабилизация, по одному тесту.**
Тест запускается на iOS. Три зелёных подряд — успех: кеш `VERIFIED_STABLE`, коммит-чекпоинт. Красный — самоизлечение: досье,
короткая проверка гипотез на живом iOS, предложение Tier-1 (с видением скриншота и reasoning), проверка, применение,
компиляция; серия обнуляется и всё сначала, максимум три попытки. Если причина в исчерпанных тестовых данных, общем
фреймворке или окружении, правок нет. Неудача: файлы возвращаются к последнему коммиту, тесту ставится `ios-needs-fix`,
отдельный коммит `isolate:`, улики сохраняются. Три теста подряд, упавшие из-за окружения, останавливают класс.

**7. Этап 5: финал.**
Класс целиком один раз на iOS, затем Android Regression Guard. Вычисляется статус (`ABORTED` > `STAGE5_FAILED` >
`COMPLETED_WITH_MANUAL_FIX` > `SUCCESS`). Запись в историю, проекция, дешёвый Tier-3 для текста, `dashboard.html` и
`report.md`. Затем финальный коммит и `git push` (если не `--no-push`; при красных итоговых проверках push требует
`--push-on-regression`).

**8. Выход.** `ConsoleSummary` печатает таблицу и путь к дашборду; процесс завершается кодом статуса.

Порядок в коде: 1 → 2 → 0 → 3 → 4 → 5 (Этапу 0 нужны слепки, поэтому он после Этапа 2).

---

## 6. Какие LLM и когда вызываются

| Ярус | Для чего | Где в коде |
|---|---|---|
| Tier-3 (дешёвый) | разметка экранов и состояний; текст отчёта | `DeltaAnalyzerService`, `Tier3ReportNarrator` |
| Tier-2 (средний) | навигация по двум MCP; сводка прохода; маппинг локаторов (первые две попытки) | `SnapshotCollectionService`, `LocatorMapper`, `GlobalComponentInitializer` |
| Tier-1 (vision + reasoning) | сложные методы; починка компиляции; самоизлечение (исследование и предложение); эскалация маппинга | `MethodAdapter`, `CompilationRepairService`, `SelfHealingService`, `LocatorMapper` |

Идёт всё через `LlmClientPort`, обёрнутый `TelemetryRecordingLlmClient`: каждый вызов записывается (ярус, модель, токены,
тест, время) в `llm_calls.jsonl` и попадает в отчёт.

---

## 7. Что лежит на диске

### Внутри целевого проекта (оба каталога в `.gitignore`, в ветки миграции не попадают)

```
.migration/                              база знаний (можно закоммитить или перенести вручную)
├── migration_cache.json                 индекс и сводка (производная)
├── screens/<ScreenName>.json            экран: состояния и сопоставленные локаторы со статусами
├── steps/<ClassFqcn>.json               проверенные методы Page Object'ов
├── tests/<TestClassFqcn>.json           статусы тестов
├── snapshots/<ScreenName>/              state_DEFAULT_android_clean.xml, state_DEFAULT_ios_clean.xml, *.png
├── history/runs.jsonl                   по строке на завершённый класс (источник проекции)
├── engine.lock                          «кто сейчас работает» (pid и классы)
└── .lock                                короткие блокировки записи кеша

migration_reports/<TestClass>/
├── dashboard.html                       самодостаточный дашборд
├── report.md                            то же текстом
├── execution.log                        журнал сессии (reasoning всегда здесь)
├── llm_calls.jsonl                      каждый вызов LLM
├── llm_wire.log                         только с -Dmigration.llm.wireLog=true
├── walkthroughs/<метод>.json            запись Android-прохода
└── failures/<метод>/                    скриншот и screen_clean.xml неудавшегося теста
```

### В git
Ветка `migrate/ios-<TestClass>`. Коммиты: `codegen: <Class>`, `checkpoint: <метод> passed 3/3 on iOS`,
`isolate: <метод> -> ios-needs-fix`. Если файлы не менялись, пустой коммит не создаётся.

### Про проекцию на 1200+ тестов
Берутся реально записанные запуски. **Ожидаемый** сценарий: среднее по последним 5 классам (с весом по числу тестов).
**Осторожный**: худший из этих классов по каждому показателю. **Оптимистичный**: лучший, но без самого первого класса (у него
кеш был пуст). Результат умножается на число оставшихся тестов; время делится на 1, 2 и 4 устройства. Если классов меньше
трёх, дашборд прямо пишет «низкая достоверность».

---

## 8. Как запускать

### Подготовка

```bash
./mvnw -pl migration-engine package -DskipTests        # соберёт migration-engine/target/migration-engine-1.0-SNAPSHOT-cli.jar
export DIAL_API_KEY=…                                    # ключ EPAM AI DIAL (DIAL_HOST необязателен)
export MIGRATION_ANDROID_CAPABILITIES=/path/capabilities-android.json
export MIGRATION_IOS_CAPABILITIES=/path/capabilities-ios.json
```

Первая сборка скачивает `maven-shade-plugin` из Maven Central. Нужны `git`, Java 25, Node.js (для `npx appium-mcp`).

### Запуск

```bash
java -jar migration-engine/target/migration-engine-1.0-SNAPSHOT-cli.jar CardsTest --project-root /work/bank-tests
java -jar … CardsTest LoginTest --no-push               # несколько классов подряд, без push
java -jar … --help
```

| Опция | Смысл |
|---|---|
| `--project-root <dir>` | корень целевого проекта (по умолчанию текущая папка) |
| `--config <file>` | JSON, который накладывается на `engine-defaults.json` |
| `--show-reasoning` | печатать блоки рассуждений LLM в консоль (в `execution.log` они есть всегда) |
| `--no-push` | коммитить, но не пушить |
| `--push-on-regression` | пушить даже при красном итоговом iOS-прогоне или Android-регрессии |

В конфиге обязательно проверьте `mcp_servers.<ANDROID|IOS>.app_id` (без него нельзя перезапускать приложение между прогонами);
остальное подходит по умолчанию (`maven`, `gate`, `agent_loop`, `tiers`, `global_components`).

### Коды выхода

| Код | Значение |
|---|---|
| 0 | всё смигрировано, итоговые проверки зелёные |
| 1 | часть тестов в `ios-needs-fix`, остальное зелёное |
| 2 | красный итоговый iOS-прогон или Android-регрессия |
| 3 | прервано: грязное дерево, не скомпилировалось, окружение недоступно |
| 64 | неверные аргументы, конфиг, не хватает переменных окружения, проект уже занят другим движком |
| 70 | сбой инфраструктуры (LLM, MCP, git, Maven) |

### Сборка, тесты и демо

```bash
./mvnw -pl migration-engine verify                       # 353 теста, ArchUnit, без устройства
./mvnw -pl migration-engine package -DskipTests          # fat jar
java -cp migration-engine/target/migration-engine-1.0-SNAPSHOT-cli.jar:migration-engine/target/test-classes \
     com.project.migration.tools.DemoDashboardMain docs/demo_dashboard.html     # пересоздать демо-дашборд
```

(`test-classes` появляется после `./mvnw -pl migration-engine test-compile`.)

### Ручная проверка на песочнице (нужны устройство, DIAL-ключ и `app_id`)
1. Закоммитьте или спрячьте свои правки.
2. Запустите `… LoginTest --project-root . --config my-config.json --no-push`.
3. Откройте `migration_reports/LoginTest/dashboard.html`, посмотрите ветку `migrate/ios-LoginTest` и её коммиты.

### Если что-то пошло не так

| Симптом | Что смотреть |
|---|---|
| код 64 «environment variable(s) not set» | экспортируйте перечисленные переменные |
| код 64 «another migration engine is running» | другой процесс держит `.migration/engine.lock` (в нём pid и классы) |
| код 3 «uncommitted changes» | закоммитьте/спрячьте/откатите файлы из списка |
| код 3 «generated code does not compile» | `migration_reports/<Class>/execution.log`, ошибки компиляции в отчёте; файлы уже откатаны |
| код 70 «LLM…» | доступность шлюза DIAL, ключ; `-Dmigration.llm.wireLog=true` для деталей |
| тест `NEEDS_MANUAL_FIX` | откройте `failures/<метод>/` и блок «Next step» в дашборде |

---

## 9. Как устроены тесты движка

- `domain/model`, `application/**`, `infrastructure/**` повторяют структуру main. Всего 62 тестовых класса.
- `testsupport` — общие фейки: `ScriptedLlm` (модель отвечает по сценарию), `FakeMcp`/`FakeMcps`, `ScriptedRunner` (Maven),
  `SnapshotGit` (git, помнящий содержимое файлов, чтобы откаты работали по-настоящему), `InMemoryCache`, `InMemorySnapshots`,
  `FakeArtifacts`, `RecordingLogger`, `MutableClock`, `BankFixture` (синтетический банковский проект в
  `src/test/resources/fixtures/bank-project`).
- Особые: `DualMcpClientAdapterTest` запускает отдельным процессом фейковый MCP-сервер по stdio; `GitServiceAdapterTest`
  работает с настоящими git-репозиториями во временных папках; `Phase4PipelineTest` и `Phase5PipelineTest` проводят целые
  конвейеры на реальных сервисах с поддельными портами; `LayerArchitectureTest` охраняет слои.

---

## 10. Чего здесь честно нет

- **Живого прогона на устройстве с настоящим DIAL и Appium я не делал.** Всё проверено на фейках и фикстурах. Первый настоящий
  запуск стоит делать на песочнице `migrator` с `--no-push`.
- Тела самих `@Test`-методов автоматически не переписываются (только Page Object'ы); если в теле есть Android-конструкции,
  движок пишет предупреждение.
- Проверка iOS-локаторов без устройства понимает ограниченный набор форм: `accessibility`; `NSPredicate` из условий через
  `AND`; `class chain` вида `**/XCUIElementTypeX[`предикат`]`. Цепочки с промежуточными уровнями отклоняются, потому что
  очиститель схлопывает обёртки.
- Тексты промптов английские, журналы и отчёты русскоязычные; дашборд подтягивает шрифт Inter из интернета, если он есть.
- Диаграммы `architecture.drawio` валидны как XML и проверены на пересечения блоков и висячие стрелки, но визуально я их в
  draw.io не просматривал; если стрелки слишком запутаны, их можно перетащить мышью.

---

## 11. Словарь

| Термин | Значение |
|---|---|
| Слепок (snapshot) | очищенный XML + скриншот одного состояния экрана на одной платформе |
| Состояние экрана | `DEFAULT`, `LOADING`, `EMPTY`, `VALIDATION_ERROR`, `BOTTOM_SHEET`, `MODAL_DIALOG`, `SUCCESS` |
| `VERIFIED_STABLE` | подтверждено тремя зелёными прогонами подряд на iOS; действует до конца миграции |
| Дельта | то, что осталось сделать после вычитания подтверждённого |
| Самоизлечение | попытка исправить упавший тест по скриншоту, XML и стектрейсу (до трёх на тест) |
| Чекпоинт | git-коммит после успешного `3/3` |
| Изоляция | откат неудачных правок + группа `ios-needs-fix` + отдельный коммит |
| Корневая причина | `LOCATOR`, `LOADING_STATE`, `KEYBOARD`, `SCROLL`, `ASSERTION_TEXT`, `TEST_DATA`, `SHARED_FRAMEWORK`, `ENVIRONMENT`, `OTHER` |
| Порт / адаптер | интерфейс в domain / его реализация в infrastructure |
| Корень композиции | `EngineAssembly`: место, где всё создаётся и соединяется |
