# Техническая Спецификация Движка Миграции Автотестов (Android -> iOS)

## 1. Вводные условия и Архитектура Памяти
1. **Code & App Freeze:** На время миграции мобильное приложение и существующие Android-тесты заморожены и не меняются. Любой снятый слепок состояния экрана и любой проверенный (`3/3 PASS`) шаг валидны до конца миграции всех 1200+ тестов.
2. **Точка входа:** На вход движку подается имя тестового класса (например, `CardsTest.java`) и булев флаг `showReasoning`.
3. **Управление секретами и тестовыми данными:**
   - Токены (`DIAL_API_KEY`, `MOBITRU_AUTH_TOKEN`) берутся только из переменных окружения.
   - Тестовые пользователи, пин-коды и способы получения OTP берутся из существующей конфигурации проекта и `@BeforeMethod` в `BaseTest` (агент никогда не придумывает фейковые учетные данные при работе с MCP).
4. **Структура локальной базы знаний (`.migration/`):**
   - `.migration/migration_cache.json` — главный накопительный JSON-реестр:
     - `global_components`: замапленные сквозные компоненты (`TopAppBar`, `BottomNavBar`, `GlobalLoadingSpinner`, `SystemNotificationShade`).
     - `screens`: статус каждого из 50+ экранов, список его состояний (`ScreenState`), замапленные локаторы (`Android -> iOS`) со статусом `VERIFIED_STABLE`.
     - `steps`: методы Page Object'ов и Step-классов, которые уже прошли проверку `3/3 PASS` на iOS.
     - `ios_learned_rules`: список обнаруженных в ходе миграции правил и особенностей iOS-приложения.
   - `.migration/snapshots/<ScreenName>/` — хранилище пар очищенных XML-слепков (`_android_clean.xml` и `_ios_clean.xml`) и скриншотов в разрезе **Состояний экрана (`ScreenState`)**:
     - `DEFAULT` — основное состояние экрана с загруженным контентом.
     - `LOADING` — состояние загрузки (спиннер, скелетон, шиммер).
     - `EMPTY` — пустое состояние (нет карт/шаблонов/истории).
     - `VALIDATION_ERROR` — состояние с инлайн-ошибкой под полем или баннером ошибки.
     - `BOTTOM_SHEET` — открытая нижняя шторка поверх экрана.
     - `MODAL_DIALOG` — системный или кастомный попап/алерт.
     - `SUCCESS` — экран или оверлей успешного завершения операции.

---

## 2. Детальные требования к базовым модулям

### 2.1. `XmlPageSourceCleaner` (Локальный Java-класс, расход 0 токенов LLM)
Отвечает за детерминированную очистку и сжатие сырого XML `pageSource` от Android MCP и iOS MCP в 15–20 раз:
1. **Белый список атрибутов (Attribute Whitelisting):**
   - Удаляет все геометрические и шумные атрибуты (`x`, `y`, `width`, `height`, `bounds`, `index`, `package`, `checkable`, `focused` и т.д.).
   - Оставляет для **iOS**: `type`, `name`, `label`, `value`, `enabled`.
   - Оставляет для **Android**: `class`, `resource-id`, `text`, `content-desc`, `enabled`, `scrollable`.
2. **Умная обработка невидимых элементов и метка `offscreen="true"`:**
   - Если узел имеет `visible="false"` (iOS) или `displayed="false"` (Android) и не несет смысловой нагрузки — вся ветка удаляется.
   - **Критическое правило для скроллов:** Если у элемента с `visible="false"` есть непустой `name`, `label`, `resource-id` или `text` (элемент находится ниже/дальше видимой области экрана), он **оставляется** в XML и получает атрибут `offscreen="true"`. Это прямой сигнал для LLM добавить нативный скролл перед обращением к элементу.
3. **Разметка семантических зон (`zone` & `scrollable_container`):**
   - Автоматически проставляет атрибуты:
     - `zone="TOP_APP_BAR"` для `XCUIElementTypeNavigationBar` / Toolbar.
     - `zone="BOTTOM_NAV_BAR"` для `XCUIElementTypeTabBar` / BottomNavigationView.
     - `zone="BOTTOM_SHEET"` для `XCUIElementTypeSheet` / BottomSheetDialog.
     - `scrollable_container="true"` для `ScrollView`, `Table`, `CollectionView`, `RecyclerView`.
4. **Схлопывание пустых оберток и сокращение тегов:**
   - Убирает пустые промежуточные контейнеры (`XCUIElementTypeOther`, `android.view.ViewGroup`, `FrameLayout`), у которых нет собственных атрибутов, поднимая дочерние элементы выше.
   - Сокращает префиксы тегов (`XCUIElementTypeButton` -> `Button`, `android.widget.TextView` -> `TextView`).
   - Маскирует любые токены (`Bearer ***MASKED***`).
   - Возвращает объект `CleaningResult(cleanedXml, rawTokensEstimate, cleanedTokensEstimate, savedTokens)`.

### 2.2. Кроссплатформенный `BaseTest` и `BasePage`
1. **`BaseTest`:**
   - Запускает драйвер на ферме Mobitru один раз в `@BeforeClass` (`UiAutomator2Options` для Android, `XCUITestOptions` для iOS с `noReset=true`).
   - В `@BeforeMethod(alwaysRun = true)` выполняет быстрый чистый рестарт приложения за 2 секунды через `terminateApp(appId)` и `activateApp(appId)`.
   - Поддерживает фильтрацию групп TestNG в XML-сьютах: `android-only`, `ios-only` и `ios-needs-fix`.
2. **`BasePage` (Инкапсуляция нативных действий Android vs iOS):**
   - `waitForLoadingToDisappear()` — ждет исчезновения глобального спиннера (`ProgressBar` на Android / `XCUIElementTypeActivityIndicator` на iOS).
   - `scrollToElementByText(String text)` — выполняет нативный скролл: `UiScrollable` на Android vs `driver.executeScript("mobile: scroll", Map.of("direction", "down", "predicateString", ...))` на iOS.
   - `scrollInsideContainer(WebElement container, String direction)` — нативный скролл внутри конкретного `ScrollView` / `Table` / `CollectionView` / `BottomSheet` или горизонтальной карусели с передачей `elementId`.
   - `navigateBack()` — жмет системную кнопку `AndroidKey.BACK` на Android, а на iOS кликает по кнопке возврата в `TopAppBar` (`XCUIElementTypeNavigationBar`).
   - `safeHideKeyboard()` — безопасно скрывает клавиатуру (на iOS ищет кнопку `Done`/`Return`/`Готово` на панели клавиатуры или выполняет `mobile: hideKeyboard` со стратегией `tapOutside`).
   - `dismissBottomSheetBySwipe(WebElement sheet)` — закрывает нижнюю шторку нативным свайпом вниз.
   - `openSystemNotifications()` — вызывает `openNotifications()` на Android vs свайп шторки сверху / работа с `com.apple.springboard` на iOS.
   - `getCleanText(WebElement element)` — решает проблему различий `getText()` на iOS (фоллбэк на атрибуты `label` и `value`) и заменяет неразрывные пробелы `\u00A0` на обычные пробелы при проверке валют и сумм.

---

## 3. Пятиэтапный Конвейер Оркестратора (`TestClassMigrationOrchestrator`)

### ЭТАП 0. Однократная инициализация Глобальных Компонентов (при первом старте)
Если секция `global_components` в `migration_cache.json` пуста, агент один раз через Android MCP и iOS MCP маппит сквозные элементы приложения (`TopAppBar`, `BottomNavBar`, `GlobalLoadingSpinner`, `SystemNotificationShade`) и сохраняет их в кеш.

### ЭТАП 1. Инициализация ветки и Дельта-Анализ (Tier-3 LLM)
1. **Git & Telemetry:** От текущей ветки создается ветка `migrate/ios-<TestClass>`. Инициализируется логгер сессии (`migration_reports/<TestClass>/execution.log`) и `TelemetryCollector`.
2. **AST-разбор и сверка с кешем (Tier-3 LLM):**
   - Через `JavaParser` извлекаются все `@Test` методы в `TestClass.java` и дерево вызываемых Page Object'ов и методов.
   - Из списка задач вычитаются все экраны, шаги и глобальные компоненты, которые уже имеют статус `VERIFIED_STABLE` в `migration_cache.json`.
   - Для каждого теста определяется список необходимых состояний экранов (`ScreenState`: `DEFAULT`, `LOADING`, `VALIDATION_ERROR`, `BOTTOM_SHEET` и т.д.).
   - Проставляются маркеры сложной платформенной логики (наличие в коде `UiScrollable`, `AndroidKey.BACK`, `openNotifications`, `GoogleWalletPage` и др.).

### ЭТАП 2. Сбор слепков состояний через Dual-MCP и Очистка (Tier-2 LLM + `XmlPageSourceCleaner`)
Выполняется только для тех экранов и их состояний (`ScreenState`), которых еще нет в `.migration/snapshots/<ScreenName>/`:
1. **Проверка локального кеша слепков:** Если файлы `state_<state>_android_clean.xml` и `state_<state>_ios_clean.xml` уже существуют в папке экрана — они берутся из диска за 0 секунд и 0 токенов.
2. **Синхронная сессия Android MCP + iOS MCP (Tier-2 LLM):**
   - Агент проходит сценарий на **Android MCP** (живой эталон, чтобы видеть реальные тексты и структуру экрана) и параллельно на **iOS MCP** до нужного экрана и вызывает требуемое состояние (например, открывает `BOTTOM_SHEET` или вызывает `VALIDATION_ERROR`).
   - Снимает сырые XML `pageSource` и скриншоты с обеих платформ.
3. **Очистка и сохранение:** Сырые XML сразу сжимаются через `XmlPageSourceCleaner` (с разметкой зон и `offscreen="true"`) и навсегда сохраняются в `.migration/snapshots/<ScreenName>/`. В телеметрию записывается количество сэкономленных токенов.

### ЭТАП 3. Генерация и Модификация Java-кода (Tier-2 LLM + Tier-1 LLM)
1. **Маппинг стандартных локаторов (Tier-2 LLM):**
   - Сопоставляет элементы из `_android_clean.xml` и `_ios_clean.xml` нужного состояния экрана.
   - Через `JavaParser` добавляет `@iOSXCUITFindBy` (приоритет: `accessibility` -> `iOSNsPredicate` -> `iOSClassChain`) над существующими `@AndroidFindBy`.
   - Если у элемента в очищенном XML стоит `offscreen="true"`, добавляет вызов нативного скролла из `BasePage` перед действием с элементом.
2. **Адаптация сложной платформенной логики (Tier-1 LLM):**
   - Включается только для методов с маркерами сложности из Этапа 1.
   - Заменяет хардкод Android-скролла (`UiScrollable`) на вызовы `scrollToElementByText()` / `scrollInsideContainer()` из `BasePage`.
   - Внедряет `waitForLoadingToDisappear()`, `navigateBack()`, `safeHideKeyboard()`, `dismissBottomSheetBySwipe()`, `getCleanText()`.
   - Для чисто платформенных тестов (Google Pay vs Apple Pay) расставляет группы `@Test(groups = {"android-only"})` и `@Test(groups = {"ios-only"})`.
3. **Проверка компиляции:** Запускается `mvn test-compile` для проверки отсутствия синтаксических ошибок во всем проекте.

### ЭТАП 4. Поштучная Стабилизация «3 из 3 PASS», Git-чекпоинты и Цикл Self-Healing (Tier-1 Vision LLM)
Берется по очереди каждый `@Test` метод внутри `TestClass.java`:
1. **Фильтр стабильности «3 из 3» (`StabilityGateService`):**
   - Тестовый метод запускается на iOS (`mvn test -Dtest=Class#method -Dplatform=iOS`) до **3 успешных прогонов подряд (`3/3 PASS`)**.
   - **При `3/3 PASS`:**
     - Задействованные в методе локаторы, шаги и состояния помечаются в `migration_cache.json` как `VERIFIED_STABLE`.
     - **Создается локальный Git-чекпоинт (`git commit -m "checkpoint: <method> passed 3/3 on iOS"`)**, фиксирующий рабочее состояние кода.
     - Осуществляется переход к следующему `@Test` методу в классе.
2. **Цикл Самоизлечения (`SelfHealingService` — при падении на 1-м, 2-м или 3-м прогоне):**
   - Максимум **3 попытки самоизлечения** на один тестовый метод.
   - Собирается досье падения: стектрейс из `testng-results.xml`, скриншот экрана iOS в момент падения (`ImageContent`), очищенный через `XmlPageSourceCleaner` XML экрана падения.
   - **Tier-1 LLM (с Vision и Reasoning)** анализирует досье (и при необходимости сверяется с живым iOS/Android MCP):
     - *Проверка идемпотентности данных:* если тест прошел 1-й раз, но упал на 2-м прогоне — проверяет, не выгорел ли тестовый юзер/лимит/OTP.
     - *Проверка состояний и UI по скриншоту:* не ушел спиннер `LOADING`, клавиатура перекрыла кнопку, не досвайпал нативный скролл, расхождение `label` vs `value` в ассерте.
   - Вносит исправление в код, **обнуляет счетчик успешных прогонов до `0`** и перезапускает проверку `3/3 PASS`.
3. **Безопасная изоляция при неудаче после 3 попыток Self-Healing:**
   - Чтобы неудачные эксперименты лечения не сломали предыдущие успешные тесты класса, код откатывается к последнему успешному Git-чекпоинту (`git checkout` измененных в ходе неудачного лечения методов).
   - Проблемный тестовый метод помечается группой `@Test(groups = {"ios-needs-fix"})`.
   - Финальный скриншот падения, стектрейс и подробный технический вердикт от Tier-1 LLM сохраняются для отчета. Конвейер переходит к следующему тесту.

### ЭТАП 5. Финальная Валидация (iOS + Android), Git Push и Генерация HTML-Инфографики
Когда все методы в `TestClass.java` обработаны:
1. **Контрольный прогон всего класса на iOS (1x):** Проверка совместной работы всех смигрированных тестов класса.
2. **Android Regression Guard (1x):** Контрольный запуск `mvn test -Dtest=TestClass -Dplatform=Android`, гарантирующий, что изменения в общих Page Object'ах не сломали Android-тесты.
3. **Сохранение `migration_cache.json`.**
4. **Генерация Отчета и Инфографики (`HtmlInfographicReportGenerator` + Tier-3 LLM):**
   - В папке `migration_reports/<TestClass>/` создаются самодостаточный `dashboard.html` (с графиками Chart.js) и `report.md`:
     - **Блок экономики токенов и стоимости:** Расход Input/Output/Reasoning токенов и стоимость в `$` по Tier-1, Tier-2, Tier-3 моделям + метрика сэкономленных токенов и `$` благодаря `XmlPageSourceCleaner` и `migration_cache.json` (Cache Hit Rate).
     - **Блок таймингов:** Общее время миграции класса, среднее время на тест и круговая диаграмма времени по этапам (Анализ vs MCP vs Генерация кода vs Прогоны 3x vs Self-Healing).
     - **Детальная таблица тестов:** Статусы (`3/3 STABLE PASS`, `HEALED & PASSED`, `NEEDS MANUAL FIX`), количество попыток лечения, прикрепленные скриншоты падений, стектрейсы, экспертный диагноз LLM и раскрывающиеся блоки `[🧠 LLM REASONING]`.
     - **Глобальный прогресс проекта:** Сколько всего из 1200+ тестов и 50+ экранов уже смигрировано.
5. **Финальный Commit & Push:** Все изменения коммитятся и отправляются в ветку `migrate/ios-<TestClass>`.