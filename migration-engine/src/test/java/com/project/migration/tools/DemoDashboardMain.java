package com.project.migration.tools;

import com.project.migration.application.report.ReportNarrative;
import com.project.migration.domain.model.MigrationStage;
import com.project.migration.domain.model.Platform;
import com.project.migration.domain.model.code.TestMethodRef;
import com.project.migration.domain.model.history.ClassRunSummary;
import com.project.migration.domain.model.llm.LlmTier;
import com.project.migration.domain.model.orchestration.MigrationExitStatus;
import com.project.migration.domain.model.report.ClassMigrationReport;
import com.project.migration.domain.model.report.FailureEvidence;
import com.project.migration.domain.model.report.HealingAttempt;
import com.project.migration.domain.model.report.HealingOutcome;
import com.project.migration.domain.model.report.ProjectProgress;
import com.project.migration.domain.model.report.ReportArtifacts;
import com.project.migration.domain.model.report.TestMethodReport;
import com.project.migration.domain.model.report.TestMigrationStatus;
import com.project.migration.domain.model.run.TestClassRunResult;
import com.project.migration.domain.model.run.TestFailure;
import com.project.migration.domain.model.run.TestOutcome;
import com.project.migration.domain.model.run.TestRunResult;
import com.project.migration.domain.model.telemetry.CacheStatistics;
import com.project.migration.domain.model.telemetry.LlmCallRecord;
import com.project.migration.domain.model.telemetry.SavingsSource;
import com.project.migration.domain.model.telemetry.TelemetrySnapshot;
import com.project.migration.domain.model.telemetry.TokenSavings;
import com.project.migration.domain.model.telemetry.TokenUsage;
import com.project.migration.domain.port.RunHistoryPort;
import com.project.migration.infrastructure.report.HtmlInfographicReportGenerator;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Renders the dashboard of an invented but realistic class migration with the real
 * {@link HtmlInfographicReportGenerator}, so the report can be looked at without a device or an LLM key.
 *
 * <pre>
 * java -cp migration-engine/target/migration-engine-1.0-SNAPSHOT-cli.jar:migration-engine/target/test-classes \
 *      com.project.migration.tools.DemoDashboardMain docs/demo_dashboard.html
 * </pre>
 */
public final class DemoDashboardMain {

    private static final String CLASS = "com.bank.tests.TransfersTest";
    private static final Instant START = Instant.parse("2026-10-30T09:12:00Z");

    private DemoDashboardMain() {
    }

    public static void main(String[] args) throws IOException {
        Path target = Path.of(args.length > 0 ? args[0] : "docs/demo_dashboard.html").toAbsolutePath();
        Path workDir = Files.createTempDirectory("demo-report");
        Path reportDir = workDir.resolve("TransfersTest");
        Path screenshot = reportDir.resolve("failures/transferToNewPayee/shot.png");
        Files.createDirectories(screenshot.getParent());
        ImageIO.write(phoneScreen(), "png", screenshot.toFile());

        HtmlInfographicReportGenerator generator = new HtmlInfographicReportGenerator(workDir, history(), report -> narrative());
        ReportArtifacts artifacts = generator.generate(report(screenshot));

        Files.createDirectories(target.getParent());
        Files.copy(artifacts.dashboardHtml(), target, StandardCopyOption.REPLACE_EXISTING);
        System.out.println("Demo dashboard: " + target + " (" + Files.size(target) / 1024 + " KB)");
    }

    private static ClassMigrationReport report(Path screenshot) {
        List<TestMethodReport> tests = new ArrayList<>();
        tests.add(stable("openTransferScreen", 2, 1_800, 420));
        tests.add(stable("transferBetweenOwnCards", 5, 2_600, 700));
        tests.add(stable("transferByPhoneNumber", 4, 2_400, 650));
        tests.add(healed("transferWithCommission", 7, 3,
                attempt(1, "Кнопка «Продолжить» перекрыта клавиатурой, тап уходит в пустоту", "добавлен safeHideKeyboard() перед тапом",
                        HealingOutcome.STILL_FAILING, "Скриншот показывает клавиатуру поверх кнопки. Android скрывает её сам при hideKeyboard(), "
                                + "на iOS нужен тап вне поля или кнопка Done. Хелпер safeHideKeyboard() уже умеет оба варианта."),
                attempt(2, "После скрытия клавиатуры сумма читается с неразрывным пробелом U+00A0", "assert через getCleanText()",
                        HealingOutcome.RESOLVED, "label=\"1 234,56 BYN\" содержит U+00A0 между тысячами; сравнение с обычным пробелом падает. "
                                + "getCleanText() нормализует пробелы на обеих платформах.")));
        tests.add(healed("transferHistoryScroll", 6, 1,
                attempt(1, "Операция «Аптека 24» вне видимой области, нативный UiScrollable не работает на iOS", "scrollToElementByText(\"Аптека 24\")",
                        HealingOutcome.RESOLVED, "Элемент помечен offscreen=\"true\" в очищенном XML iOS. mobile: scroll с predicateString решает задачу.")));
        tests.add(stable("transferLimitsInfo", 3, 2_000, 540));
        tests.add(stable("transferToSavedPayee", 4, 2_700, 720));
        tests.add(broken("transferToNewPayee", screenshot));
        tests.add(dataExhausted("transferWithOtp"));
        tests.add(new TestMethodReport(new TestMethodRef(CLASS, "transferViaGooglePay"), TestMigrationStatus.PLATFORM_SPECIFIC, 0, 0,
                List.of(), Optional.empty(), List.of(), Map.of()));
        tests.add(new TestMethodReport(new TestMethodRef(CLASS, "transferScreenTitle"), TestMigrationStatus.SKIPPED_ALREADY_VERIFIED, 0, 0,
                List.of(), Optional.empty(), List.of(), Map.of()));
        tests.add(stable("transferCancel", 2, 1_500, 380));

        List<LlmCallRecord> calls = new ArrayList<>();
        tests.forEach(t -> calls.addAll(t.llmCalls()));
        calls.add(call(LlmTier.TIER_3, null, MigrationStage.STAGE_1_DELTA_ANALYSIS, 14_200, 1_900, 0));
        for (int i = 0; i < 9; i++) {
            calls.add(call(LlmTier.TIER_2, null, MigrationStage.STAGE_2_SNAPSHOTS, 21_000 + i * 1_300, 1_100, 0));
        }
        for (int i = 0; i < 5; i++) {
            calls.add(call(LlmTier.TIER_2, null, MigrationStage.STAGE_3_CODEGEN, 9_800, 1_400, 0));
        }
        calls.add(call(LlmTier.TIER_3, null, MigrationStage.STAGE_5_FINALIZE, 3_100, 600, 0));

        Map<LlmTier, BigDecimal> cost = new EnumMap<>(LlmTier.class);
        cost.put(LlmTier.TIER_1, new BigDecimal("2.84"));
        cost.put(LlmTier.TIER_2, new BigDecimal("0.97"));
        cost.put(LlmTier.TIER_3, new BigDecimal("0.04"));
        Map<MigrationStage, Duration> stages = new EnumMap<>(MigrationStage.class);
        stages.put(MigrationStage.STAGE_1_DELTA_ANALYSIS, Duration.ofSeconds(52));
        stages.put(MigrationStage.STAGE_2_SNAPSHOTS, Duration.ofMinutes(9).plusSeconds(20));
        stages.put(MigrationStage.STAGE_3_CODEGEN, Duration.ofMinutes(4).plusSeconds(5));
        stages.put(MigrationStage.STAGE_4_STABILIZATION, Duration.ofMinutes(31).plusSeconds(40));
        stages.put(MigrationStage.STAGE_4_SELF_HEALING, Duration.ofMinutes(17).plusSeconds(30));
        stages.put(MigrationStage.STAGE_5_FINALIZE, Duration.ofMinutes(6).plusSeconds(10));
        Duration total = stages.values().stream().reduce(Duration.ZERO, Duration::plus);

        TelemetrySnapshot telemetry = new TelemetrySnapshot(calls,
                List.of(new TokenSavings(SavingsSource.XML_CLEANING, 612_000), new TokenSavings(SavingsSource.SNAPSHOT_CACHE, 148_000),
                        new TokenSavings(SavingsSource.MIGRATION_CACHE, 96_000)),
                new CacheStatistics(41, 17), cost, new BigDecimal("2.57"), stages, total);

        TestClassRunResult ios = new TestClassRunResult(CLASS, Platform.IOS, List.of(
                run("openTransferScreen", Platform.IOS, TestOutcome.PASSED), run("transferBetweenOwnCards", Platform.IOS, TestOutcome.PASSED),
                run("transferByPhoneNumber", Platform.IOS, TestOutcome.PASSED), run("transferWithCommission", Platform.IOS, TestOutcome.PASSED),
                run("transferHistoryScroll", Platform.IOS, TestOutcome.PASSED), run("transferLimitsInfo", Platform.IOS, TestOutcome.PASSED),
                run("transferToSavedPayee", Platform.IOS, TestOutcome.PASSED), run("transferCancel", Platform.IOS, TestOutcome.PASSED)),
                Duration.ofMinutes(5));
        TestClassRunResult android = new TestClassRunResult(CLASS, Platform.ANDROID, List.of(
                run("openTransferScreen", Platform.ANDROID, TestOutcome.PASSED), run("transferHistoryScroll", Platform.ANDROID, TestOutcome.PASSED),
                run("transferWithCommission", Platform.ANDROID, TestOutcome.PASSED), run("transferViaGooglePay", Platform.ANDROID, TestOutcome.PASSED)),
                Duration.ofMinutes(4));

        return new ClassMigrationReport(CLASS, "migrate/ios-TransfersTest", START, START.plus(total), tests, stages, telemetry,
                Optional.of(ios), Optional.of(android), new ProjectProgress(1200, 187, 52, 14), MigrationExitStatus.COMPLETED_WITH_MANUAL_FIX,
                List.of());
    }

    private static TestMethodReport stable(String name, int minutes, long input, long output) {
        TestMethodRef test = new TestMethodRef(CLASS, name);
        return new TestMethodReport(test, TestMigrationStatus.STABLE_PASS, 3, 3, List.of(), Optional.empty(),
                List.of(call(LlmTier.TIER_2, test, MigrationStage.STAGE_4_STABILIZATION, input, output, 0)),
                Map.of(MigrationStage.STAGE_4_STABILIZATION, Duration.ofMinutes(minutes).plusSeconds(name.length() * 3L)));
    }

    private static TestMethodReport healed(String name, int minutes, int runsExtra, HealingAttempt... attempts) {
        TestMethodRef test = new TestMethodRef(CLASS, name);
        List<LlmCallRecord> calls = new ArrayList<>();
        for (int i = 0; i < attempts.length; i++) {
            calls.add(call(LlmTier.TIER_1, test, MigrationStage.STAGE_4_SELF_HEALING, 38_000 + i * 6_000, 3_900, 5_200));
            calls.add(call(LlmTier.TIER_1, test, MigrationStage.STAGE_4_SELF_HEALING, 12_000, 1_200, 0));
        }
        return new TestMethodReport(test, TestMigrationStatus.HEALED_AND_PASSED, 3, 3 + runsExtra + attempts.length, List.of(attempts),
                Optional.empty(), calls, Map.of(MigrationStage.STAGE_4_STABILIZATION, Duration.ofMinutes(3),
                MigrationStage.STAGE_4_SELF_HEALING, Duration.ofMinutes(minutes)));
    }

    private static TestMethodReport broken(String name, Path screenshot) {
        TestMethodRef test = new TestMethodRef(CLASS, name);
        List<HealingAttempt> attempts = List.of(
                attempt(1, "Поле «Получатель» не находится по accessibility id", "добавлен iOSNsPredicate по label", HealingOutcome.STILL_FAILING,
                        "В очищенном XML iOS поле называется «Номер счёта или телефон», а не «Получатель»."),
                attempt(2, "Предикат совпадает с двумя элементами (поле и подсказка)", "сужен предикат по type", HealingOutcome.COMPILATION_FAILED, null),
                attempt(3, "После сужения экран не открывается: бэкенд получателей не отвечает", "правок нет", HealingOutcome.STILL_FAILING,
                        "Скриншот: бесконечный спиннер LOADING после тапа «Новый получатель». Похоже на проблему тестового стенда."));
        List<LlmCallRecord> calls = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            calls.add(call(LlmTier.TIER_1, test, MigrationStage.STAGE_4_SELF_HEALING, 52_000, 4_400, 7_800));
            calls.add(call(LlmTier.TIER_1, test, MigrationStage.STAGE_4_SELF_HEALING, 14_000, 1_500, 0));
        }
        FailureEvidence evidence = new FailureEvidence("org.openqa.selenium.NoSuchElementException: An element could not be located on the page "
                + "using the given search parameters. (Using: -ios predicate string, value: label == 'Получатель')\n"
                + "  at io.appium.java_client.pagefactory...(AppiumElementLocator.java:98)\n  at alex.valker91.page.TransferPage.enterPayee(TransferPage.java:41)\n"
                + "  at com.bank.tests.TransfersTest.transferToNewPayee(TransfersTest.java:77)",
                Optional.of(screenshot), Optional.of(screenshot.getParent().resolve("screen_clean.xml")),
                "self-healing used all 3 attempts and the test still fails: NoSuchElementException on the payee field. The last diagnosis points to "
                        + "a stuck LOADING state after tapping «Новый получатель»: probably the test stand, not the locator.");
        return new TestMethodReport(test, TestMigrationStatus.NEEDS_MANUAL_FIX, 0, 7, attempts, Optional.of(evidence), calls,
                Map.of(MigrationStage.STAGE_4_STABILIZATION, Duration.ofMinutes(4), MigrationStage.STAGE_4_SELF_HEALING, Duration.ofMinutes(14)));
    }

    private static TestMethodReport dataExhausted(String name) {
        TestMethodRef test = new TestMethodRef(CLASS, name);
        FailureEvidence evidence = new FailureEvidence("java.lang.AssertionError: OTP rejected: attempts limit reached for user test-user-07",
                Optional.empty(), Optional.empty(), "test data exhausted (user/limit/OTP): the OTP limit of test-user-07 is used up; the code is fine");
        return new TestMethodReport(test, TestMigrationStatus.NEEDS_MANUAL_FIX, 1, 2,
                List.of(), Optional.of(evidence), List.of(call(LlmTier.TIER_1, test, MigrationStage.STAGE_4_SELF_HEALING, 36_000, 2_000, 3_100)),
                Map.of(MigrationStage.STAGE_4_STABILIZATION, Duration.ofMinutes(2), MigrationStage.STAGE_4_SELF_HEALING, Duration.ofMinutes(2)));
    }

    private static HealingAttempt attempt(int number, String diagnosis, String change, HealingOutcome outcome, String reasoning) {
        return new HealingAttempt(number, diagnosis, Optional.ofNullable(reasoning), change, outcome);
    }

    private static LlmCallRecord call(LlmTier tier, TestMethodRef test, MigrationStage stage, long input, long output, long reasoning) {
        String model = switch (tier) {
            case TIER_1 -> "claude-opus-5-5";
            case TIER_2 -> "claude-sonnet-5-5";
            case TIER_3 -> "claude-haiku-5-5";
        };
        return new LlmCallRecord(START, stage, tier, model, "demo", new TokenUsage(input, output, reasoning, input / 5),
                Duration.ofSeconds(3), Optional.ofNullable(test));
    }

    private static TestRunResult run(String name, Platform platform, TestOutcome outcome) {
        return new TestRunResult(new TestMethodRef(CLASS, name), platform, outcome,
                outcome == TestOutcome.PASSED ? Optional.empty() : Optional.of(new TestFailure("failed", "trace")),
                Duration.ofSeconds(40), Optional.empty());
    }

    private static RunHistoryPort history() {
        List<ClassRunSummary> runs = new ArrayList<>();
        String[] names = {"LoginTest", "HomeTest", "CardsTest", "PaymentsTest", "SettingsTest", "ProfileTest", "TransfersTest"};
        int[] processed = {6, 9, 11, 14, 8, 10, 10};
        double[] cost = {14.2, 17.5, 14.1, 15.4, 5.7, 6.9, 3.85};
        int[] minutes = {96, 118, 101, 124, 52, 61, 60};
        double[] hit = {0.0, 0.18, 0.31, 0.38, 0.52, 0.61, 0.71};
        for (int i = 0; i < names.length; i++) {
            runs.add(new ClassRunSummary("com.bank.tests." + names[i], START.minus(Duration.ofDays(names.length - i)),
                    MigrationExitStatus.SUCCESS, processed[i], processed[i] - 1, 1, 0, 2, (long) (cost[i] * 210_000), BigDecimal.valueOf(cost[i]),
                    (long) (hit[i] * 900_000), BigDecimal.valueOf(hit[i] * 2.7), hit[i], Duration.ofMinutes(minutes[i]),
                    Duration.ofMinutes(minutes[i] * 6 / 10), Duration.ofMinutes(minutes[i] / 5)));
        }
        return new RunHistoryPort() {
            @Override
            public void append(ClassRunSummary summary) {
            }

            @Override
            public List<ClassRunSummary> findAll() {
                return runs;
            }
        };
    }

    private static ReportNarrative narrative() {
        return new ReportNarrative(List.of(
                "TransfersTest мигрирован на iOS почти целиком: 8 тестов проходят 3 из 3, два из них после самоизлечения.",
                "Самоизлечение решило две типовые проблемы iOS: клавиатура поверх кнопки и неразрывный пробел в суммах. Обе вошли в правила iOS и "
                        + "будут подмешиваться в следующие классы.",
                "Тест transferToNewPayee остановился на зависшем спиннере после тапа «Новый получатель»: это похоже на тестовый стенд, а не на локатор.",
                "transferWithOtp не требует правок кода: у тестового пользователя исчерпан лимит OTP.",
                "Кеш уже работает: 71% обращений обслужены без LLM и MCP, стоимость на тест упала с $2.37 в первом классе до $0.39 в этом."),
                Map.of("transferToNewPayee", "Проверьте, отвечает ли бэкенд получателей на тестовом стенде; затем откройте скриншот в failures/ и убедитесь, "
                                + "что спиннер LOADING исчезает за разумное время. Если да, добавьте waitForLoadingToDisappear() после тапа.",
                        "transferWithOtp", "Обновите тестового пользователя или сбросьте лимит OTP на стенде и запустите тест ещё раз: код менять не нужно."),
                true);
    }

    private static BufferedImage phoneScreen() {
        int w = 1170;
        int h = 2532;
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(0xF2F2F7));
        g.fillRect(0, 0, w, h);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, 260);
        g.setColor(new Color(0x111111));
        g.setFont(new Font("SansSerif", Font.BOLD, 56));
        g.drawString("Новый получатель", 60, 190);
        g.setFont(new Font("SansSerif", Font.PLAIN, 44));
        g.setColor(new Color(0x007AFF));
        g.drawString("‹ Назад", 60, 110);
        g.setColor(Color.WHITE);
        g.fillRoundRect(50, 330, w - 100, 150, 40, 40);
        g.setColor(new Color(0x8E8E93));
        g.setFont(new Font("SansSerif", Font.PLAIN, 40));
        g.drawString("Номер счёта или телефон", 90, 420);
        g.setColor(Color.WHITE);
        g.fillRoundRect(50, 520, w - 100, 150, 40, 40);
        g.setColor(new Color(0x8E8E93));
        g.drawString("Сумма", 90, 610);
        g.setColor(new Color(0x34C759));
        g.fillRoundRect(50, 760, w - 100, 140, 70, 70);
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.BOLD, 46));
        g.drawString("Продолжить", 430, 850);
        g.setColor(new Color(0x00000099, true));
        g.fillRect(0, 0, w, h);
        g.setColor(Color.WHITE);
        g.fillRoundRect(w / 2 - 120, h / 2 - 120, 240, 240, 50, 50);
        g.setColor(new Color(0x007AFF));
        g.setStroke(new java.awt.BasicStroke(14, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
        g.drawArc(w / 2 - 55, h / 2 - 55, 110, 110, 30, 270);
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.PLAIN, 38));
        g.drawString("LOADING: спиннер не исчезает", w / 2 - 270, h / 2 + 230);
        g.dispose();
        return image;
    }
}
