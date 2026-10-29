package com.project.migration.testsupport;

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
import com.project.migration.domain.model.report.TestMethodReport;
import com.project.migration.domain.model.report.TestMigrationStatus;
import com.project.migration.domain.model.run.TestClassRunResult;
import com.project.migration.domain.model.telemetry.CacheStatistics;
import com.project.migration.domain.model.telemetry.LlmCallRecord;
import com.project.migration.domain.model.telemetry.SavingsSource;
import com.project.migration.domain.model.telemetry.TelemetrySnapshot;
import com.project.migration.domain.model.telemetry.TokenSavings;
import com.project.migration.domain.model.telemetry.TokenUsage;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A class report with one test of every interesting kind, and run summaries for the projection.
 */
public final class ReportFixtures {

    public static final String CLASS = "com.bank.tests.CardsTest";
    public static final Instant START = Instant.parse("2026-10-30T10:00:00Z");
    public static final TestMethodRef STABLE = new TestMethodRef(CLASS, "openCardDetails");
    public static final TestMethodRef HEALED = new TestMethodRef(CLASS, "backFromCards");
    public static final TestMethodRef BROKEN = new TestMethodRef(CLASS, "loginWithOverload");
    public static final TestMethodRef ANDROID_ONLY = new TestMethodRef(CLASS, "payWithGooglePay");
    public static final String EVIL = "<script>alert('x')</script>\"&";

    private ReportFixtures() {
    }

    public static ClassMigrationReport report(Path screenshot, MigrationExitStatus status) {
        TestMethodReport stable = new TestMethodReport(STABLE, TestMigrationStatus.STABLE_PASS, 3, 3, List.of(), Optional.empty(),
                List.of(call(LlmTier.TIER_2, STABLE, 1_000, 200)), Map.of(MigrationStage.STAGE_4_STABILIZATION, Duration.ofMinutes(2)));
        TestMethodReport healed = new TestMethodReport(HEALED, TestMigrationStatus.HEALED_AND_PASSED, 3, 5,
                List.of(new HealingAttempt(1, "tile has no iOS locator", Optional.of("thinking about </script> tiles"),
                        "added an accessibility locator", HealingOutcome.RESOLVED)), Optional.empty(),
                List.of(call(LlmTier.TIER_1, HEALED, 5_000, 1_500)),
                Map.of(MigrationStage.STAGE_4_STABILIZATION, Duration.ofMinutes(3), MigrationStage.STAGE_4_SELF_HEALING, Duration.ofMinutes(4)));
        TestMethodReport broken = new TestMethodReport(BROKEN, TestMigrationStatus.NEEDS_MANUAL_FIX, 0, 4,
                List.of(new HealingAttempt(1, EVIL, Optional.empty(), "tried " + EVIL, HealingOutcome.STILL_FAILING),
                        new HealingAttempt(2, "still wrong", Optional.empty(), "changed the predicate", HealingOutcome.COMPILATION_FAILED)),
                Optional.of(new FailureEvidence("java.lang.AssertionError: " + EVIL + "\n  at CardsTest.java:41",
                        Optional.ofNullable(screenshot), Optional.of(Path.of("failures/loginWithOverload/screen_clean.xml")),
                        "self-healing used all 3 attempts: " + EVIL)),
                List.of(call(LlmTier.TIER_1, BROKEN, 9_000, 3_000)), Map.of(MigrationStage.STAGE_4_SELF_HEALING, Duration.ofMinutes(9)));
        TestMethodReport platform = new TestMethodReport(ANDROID_ONLY, TestMigrationStatus.PLATFORM_SPECIFIC, 0, 0, List.of(),
                Optional.empty(), List.of(), Map.of());

        TelemetrySnapshot telemetry = new TelemetrySnapshot(
                List.of(call(LlmTier.TIER_2, STABLE, 1_000, 200), call(LlmTier.TIER_1, HEALED, 5_000, 1_500),
                        call(LlmTier.TIER_1, BROKEN, 9_000, 3_000), call(LlmTier.TIER_3, null, 400, 100)),
                List.of(new TokenSavings(SavingsSource.XML_CLEANING, 120_000), new TokenSavings(SavingsSource.MIGRATION_CACHE, 8_000)),
                new CacheStatistics(14, 6), Map.of(LlmTier.TIER_1, new BigDecimal("0.45"), LlmTier.TIER_2, new BigDecimal("0.05"),
                LlmTier.TIER_3, new BigDecimal("0.01")), new BigDecimal("0.38"),
                Map.of(MigrationStage.STAGE_1_DELTA_ANALYSIS, Duration.ofSeconds(30), MigrationStage.STAGE_2_SNAPSHOTS, Duration.ofMinutes(4),
                        MigrationStage.STAGE_3_CODEGEN, Duration.ofMinutes(2), MigrationStage.STAGE_4_STABILIZATION, Duration.ofMinutes(5),
                        MigrationStage.STAGE_4_SELF_HEALING, Duration.ofMinutes(13), MigrationStage.STAGE_5_FINALIZE, Duration.ofMinutes(3)),
                Duration.ofMinutes(27));
        TestClassRunResult ios = new TestClassRunResult(CLASS, Platform.IOS, List.of(run(STABLE, Platform.IOS, true),
                run(HEALED, Platform.IOS, true)), Duration.ofMinutes(2));
        TestClassRunResult android = new TestClassRunResult(CLASS, Platform.ANDROID, List.of(run(STABLE, Platform.ANDROID, true),
                run(BROKEN, Platform.ANDROID, false)), Duration.ofMinutes(2));
        return new ClassMigrationReport(CLASS, "migrate/ios-CardsTest", START, START.plus(Duration.ofMinutes(27)),
                List.of(stable, platform, healed, broken), telemetry.stageDurations(), telemetry, Optional.of(ios),
                Optional.of(android), new ProjectProgress(1200, 140, 50, 12), status,
                status == MigrationExitStatus.STAGE5_FAILED ? List.of(BROKEN) : List.of());
    }

    public static List<ClassRunSummary> history(int runs) {
        return java.util.stream.IntStream.rangeClosed(1, runs).mapToObj(i -> new ClassRunSummary("com.bank.Class" + i,
                START.plus(Duration.ofDays(i)), MigrationExitStatus.SUCCESS, 10, 9, 1, 0, 1, 100_000L - i * 10_000L,
                new BigDecimal("10.00").subtract(BigDecimal.valueOf(i)), 20_000, new BigDecimal("0.50"), Math.min(0.9, 0.2 + i * 0.1),
                Duration.ofMinutes(60 - i * 5L), Duration.ofMinutes(40), Duration.ofMinutes(10))).toList();
    }

    private static LlmCallRecord call(LlmTier tier, TestMethodRef test, long input, long output) {
        return new LlmCallRecord(START, MigrationStage.STAGE_3_CODEGEN, tier, "model", "purpose", new TokenUsage(input, output, 0, 0),
                Duration.ofSeconds(2), Optional.ofNullable(test));
    }

    private static com.project.migration.domain.model.run.TestRunResult run(TestMethodRef test, Platform platform, boolean passed) {
        return passed ? ScriptedRunner.passed(test, platform) : ScriptedRunner.failed(test, platform, "boom");
    }
}
