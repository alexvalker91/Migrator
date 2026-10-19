package alex.valker91.tm;

import alex.valker91.tm.ai.*;
import alex.valker91.tm.codegen.PageObjectUpdater;
import alex.valker91.tm.config.MigrationConfig;
import alex.valker91.tm.device.MobileDeviceSession;
import alex.valker91.tm.metrics.MetricsTracker;
import alex.valker91.tm.model.IosLocator;
import alex.valker91.tm.model.TestStep;
import alex.valker91.tm.sanitizer.XmlSanitizer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class MigrationRunner {

    private final MetricsTracker metrics = new MetricsTracker();

    // Внедряем декларативные AI Services
    private final PlannerAiService plannerService = AiServiceFactory.createPlannerService(metrics);
    private final LocatorResolverAiService locatorResolverService = AiServiceFactory.createLocatorResolverService(metrics);

    public static void main(String[] args) {
        MigrationRunner migrator = new MigrationRunner();
        migrator.migrate("LoginTest", "loginNegativeTest");
    }

    public void migrate(String testClassName, String testMethodName) {
        System.out.println("==================================================");
        System.out.println("  СТАРТ МИГРАЦИИ: " + testClassName + " -> " + testMethodName);
        System.out.println("==================================================");

        try {
            // 1. Читаем тест и строим план шагов через AI Service
            Path testPath = PageObjectUpdater.findFilePath(MigrationConfig.TESTS_DIR, testClassName + ".java");
            if (testPath == null) throw new RuntimeException("Не найден тест: " + testClassName);

            String testClassSource = Files.readString(testPath);

            // ВСТАВКА 1: Получение плана в одну строчку через @SystemMessage / @UserMessage:
            List<TestStep> steps = plannerService.planSteps(testMethodName, testClassSource);
            System.out.println("Построен план из " + steps.size() + " шагов.");

            // 2. Открываем параллельные сессии Android и iOS
            try (var android = new MobileDeviceSession("android", MigrationConfig.ANDROID_CAPS);
                 var ios = new MobileDeviceSession("ios", MigrationConfig.IOS_CAPS)) {

                CompletableFuture.allOf(
                        CompletableFuture.runAsync(android::startSession),
                        CompletableFuture.runAsync(ios::startSession)
                ).join();

                System.out.println("\n Приложения успешно запущены на обоих устройствах!\n");

                // 3. Синхронное выполнение шагов
                for (TestStep step : steps) {
                    processStep(step, android, ios);
                }

                System.out.println("\n=== МИГРАЦИЯ ТЕСТА УСПЕШНО ЗАВЕРШЕНА! ===");
            }

            // 4. Печать аналитики расхода токенов
            metrics.printDashboard();

        } catch (Exception e) {
            System.err.println("Ошибка миграции: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void processStep(TestStep step, MobileDeviceSession android, MobileDeviceSession ios) {
        System.out.println("\n--- Шаг: " + step.actionType + " | " + step.targetElementDescription + " ---");

        // Санитизация XML
        String cleanAndroidXml = XmlSanitizer.sanitize(android.getPageSource());
        String cleanIosXml = XmlSanitizer.sanitize(ios.getPageSource());

        // ВСТАВКА 2: Получение локатора в одну строчку без регулярных выражений и Jackson:
        IosLocator iosLocator = locatorResolverService.resolveLocator(step, cleanAndroidXml, cleanIosXml);
        System.out.println("  -> Найден iOS локатор: [" + iosLocator.strategy() + "] = \"" + iosLocator.value() + "\"");

        // Обновление Page Object без комментариев
        if (step.pageClassName != null && !step.pageClassName.isBlank()) {
            PageObjectUpdater.injectLocator(step.pageClassName, step.elementIdentifier, iosLocator);
        }

        // Выполнение действия на обоих девайсах
        execute(android, step.actionType, step.androidStrategy, step.androidSelector, step.inputValue);
        execute(ios, step.actionType, iosLocator.strategy(), iosLocator.value(), step.inputValue);
    }

    private void execute(MobileDeviceSession session, String action, String strategy, String selector, String value) {
        if ("tap".equalsIgnoreCase(action) || "click".equalsIgnoreCase(action)) {
            session.tap(strategy, selector);
        } else if ("type".equalsIgnoreCase(action)) {
            session.type(strategy, selector, value);
        } else if ("assert_visible".equalsIgnoreCase(action)) {
            session.assertVisible(strategy, selector);
        }
    }
}