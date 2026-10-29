package com.project.migration.application.telemetry;

import com.project.migration.domain.model.MigrationStage;
import com.project.migration.domain.model.code.TestMethodRef;
import com.project.migration.domain.model.config.EngineConfig;
import com.project.migration.domain.model.llm.LlmRequest;
import com.project.migration.domain.model.llm.LlmResponse;
import com.project.migration.domain.model.llm.LlmTier;
import com.project.migration.domain.model.llm.TierModelConfig;
import com.project.migration.domain.model.llm.UserMessage;
import com.project.migration.domain.model.screen.CleaningResult;
import com.project.migration.domain.model.telemetry.LlmCallRecord;
import com.project.migration.domain.model.telemetry.ModelPricing;
import com.project.migration.domain.model.telemetry.SavingsSource;
import com.project.migration.domain.model.telemetry.TelemetrySnapshot;
import com.project.migration.domain.model.telemetry.TestTelemetry;
import com.project.migration.domain.model.telemetry.TokenUsage;
import com.project.migration.domain.port.LlmClientPort;
import com.project.migration.domain.port.MigrationLoggerPort;
import com.project.migration.domain.port.TelemetrySinkPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TelemetryCollectorTest {

    private static final String CLASS = "webdriverio.LoginTest";
    private static final TestMethodRef TEST = new TestMethodRef(CLASS, "loginNegativeTest");

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-29T10:00:00Z"));
    private final RecordingSink sink = new RecordingSink();
    private final RecordingLogger logger = new RecordingLogger();
    private TelemetryCollector collector;

    @BeforeEach
    void setUp() {
        EngineConfig config = EngineConfig.withDefaults(Path.of("/work"), false, List.of(
                        new TierModelConfig(LlmTier.TIER_1, "opus", Optional.of("astra-reasoning"), true),
                        new TierModelConfig(LlmTier.TIER_2, "sonnet", Optional.empty(), true),
                        new TierModelConfig(LlmTier.TIER_3, "haiku", Optional.empty(), true)),
                List.of(pricing("opus", "5", "25"), pricing("astra-reasoning", "5", "25"), pricing("sonnet", "3", "15")));
        collector = new TelemetryCollector(config, clock, sink, logger);
        collector.startSession(CLASS);
    }

    @Test
    void pricesCallsPerTierAndPersistsEachCallImmediately() {
        collector.enterStage(MigrationStage.STAGE_3_CODEGEN);
        collector.enterTest(TEST);
        collector.recordLlmCall(request(LlmTier.TIER_1), response("astra-reasoning", new TokenUsage(1_000_000, 100_000, 0, 0)));
        collector.exitTest();
        collector.recordLlmCall(request(LlmTier.TIER_2), response("sonnet", new TokenUsage(500_000, 0, 0, 0)));

        TelemetrySnapshot snapshot = collector.snapshot();

        assertThat(snapshot.costByTier().get(LlmTier.TIER_1)).isEqualByComparingTo("7.5");
        assertThat(snapshot.costByTier().get(LlmTier.TIER_2)).isEqualByComparingTo("1.5");
        assertThat(snapshot.llmCalls()).extracting(LlmCallRecord::testMethod)
                .containsExactly(Optional.of(TEST), Optional.empty());
        assertThat(sink.appended).hasSize(2).allSatisfy(line -> assertThat(line).startsWith(CLASS + "|"));
    }

    @Test
    void unpricedModelCostsNothingAndIsReportedOnce() {
        collector.recordLlmCall(request(LlmTier.TIER_3), response("haiku", new TokenUsage(10, 10, 0, 0)));
        collector.recordLlmCall(request(LlmTier.TIER_3), response("haiku", new TokenUsage(10, 10, 0, 0)));

        assertThat(collector.snapshot().costByTier().get(LlmTier.TIER_3)).isEqualByComparingTo("0");
        collector.snapshot();
        assertThat(logger.warnings).singleElement().asString().contains("haiku");
    }

    @Test
    void savingsArePricedAtTier2InputPrice() {
        collector.recordCleaning(CleaningResult.of("<hierarchy/>", 900_000, 100_000));
        collector.recordSavings(SavingsSource.SNAPSHOT_CACHE, 200_000);
        collector.recordSavings(SavingsSource.MIGRATION_CACHE, 0);

        TelemetrySnapshot snapshot = collector.snapshot();

        assertThat(snapshot.savedTokens(SavingsSource.XML_CLEANING)).isEqualTo(800_000);
        assertThat(snapshot.totalSavedTokens()).isEqualTo(1_000_000);
        assertThat(snapshot.savings()).hasSize(2);
        assertThat(snapshot.estimatedSavedCost()).isEqualByComparingTo("3");
    }

    @Test
    void stageTimesAddUpWhenAStageIsReEntered() {
        collector.enterStage(MigrationStage.STAGE_4_STABILIZATION);
        clock.advance(Duration.ofSeconds(30));
        collector.enterStage(MigrationStage.STAGE_3_CODEGEN);
        clock.advance(Duration.ofSeconds(10));
        collector.enterStage(MigrationStage.STAGE_4_STABILIZATION);
        clock.advance(Duration.ofSeconds(20));

        TelemetrySnapshot running = collector.snapshot();
        collector.finish();
        clock.advance(Duration.ofSeconds(100));
        TelemetrySnapshot finished = collector.snapshot();

        assertThat(running.stageDuration(MigrationStage.STAGE_4_STABILIZATION)).isEqualTo(Duration.ofSeconds(50));
        assertThat(running.stageDuration(MigrationStage.STAGE_3_CODEGEN)).isEqualTo(Duration.ofSeconds(10));
        assertThat(running.totalDuration()).isEqualTo(Duration.ofSeconds(60));
        assertThat(finished.stageDuration(MigrationStage.STAGE_4_STABILIZATION)).isEqualTo(Duration.ofSeconds(50));
    }

    @Test
    void timeAndCallsAreAttributedToTheTestThatWasCurrent() {
        TestMethodRef other = new TestMethodRef(CLASS, "otherTest");
        collector.enterStage(MigrationStage.STAGE_4_STABILIZATION);
        clock.advance(Duration.ofSeconds(5));
        collector.enterTest(TEST);
        clock.advance(Duration.ofSeconds(20));
        collector.recordLlmCall(request(LlmTier.TIER_1), response("opus", new TokenUsage(1_000, 500, 0, 0)));
        collector.enterStage(MigrationStage.STAGE_4_SELF_HEALING);
        clock.advance(Duration.ofSeconds(40));
        collector.enterStage(MigrationStage.STAGE_4_STABILIZATION);
        clock.advance(Duration.ofSeconds(10));
        collector.exitTest();
        collector.enterTest(other);
        clock.advance(Duration.ofSeconds(7));
        collector.recordLlmCall(request(LlmTier.TIER_2), response("sonnet", new TokenUsage(100, 50, 0, 0)));

        TestTelemetry first = collector.testSnapshot(TEST);
        TestTelemetry second = collector.testSnapshot(other);
        collector.exitTest();

        assertThat(first.stageTimings()).containsEntry(MigrationStage.STAGE_4_STABILIZATION, Duration.ofSeconds(30))
                .containsEntry(MigrationStage.STAGE_4_SELF_HEALING, Duration.ofSeconds(40));
        assertThat(first.calls()).hasSize(1);
        assertThat(first.totalTokens()).isEqualTo(1_500);
        assertThat(second.stageTimings()).containsEntry(MigrationStage.STAGE_4_STABILIZATION, Duration.ofSeconds(7));
        assertThat(second.totalTokens()).isEqualTo(150);
        assertThat(collector.snapshot().stageDuration(MigrationStage.STAGE_4_STABILIZATION)).isEqualTo(Duration.ofSeconds(42));
        assertThat(collector.testSnapshot(new TestMethodRef(CLASS, "never")).calls()).isEmpty();
    }

    @Test
    void cacheHitRateCountsExplicitLookups() {
        collector.cacheHit();
        collector.cacheHit();
        collector.cacheHit();
        collector.cacheMiss();

        assertThat(collector.snapshot().cacheStatistics().hitRate()).isEqualTo(0.75);
    }

    @Test
    void newSessionStartsFromScratch() {
        collector.recordLlmCall(request(LlmTier.TIER_2), response("sonnet", new TokenUsage(1, 1, 0, 0)));
        collector.cacheHit();

        collector.startSession("webdriverio.CardsTest");

        assertThat(collector.snapshot().llmCalls()).isEmpty();
        assertThat(collector.snapshot().cacheStatistics().hits()).isZero();
    }

    @Test
    void sinkFailureDoesNotStopTheMigration() {
        sink.failing = true;

        collector.recordLlmCall(request(LlmTier.TIER_2), response("sonnet", new TokenUsage(1, 1, 0, 0)));

        assertThat(collector.snapshot().llmCalls()).hasSize(1);
        assertThat(logger.warnings).singleElement().asString().contains("persist telemetry");
    }

    @Test
    void requiresASession() {
        TelemetryCollector fresh = new TelemetryCollector(EngineConfig.withDefaults(Path.of("/w"), false, List.of(
                new TierModelConfig(LlmTier.TIER_1, "a", Optional.empty(), true),
                new TierModelConfig(LlmTier.TIER_2, "b", Optional.empty(), true),
                new TierModelConfig(LlmTier.TIER_3, "c", Optional.empty(), true)), List.of()), clock, sink, logger);

        assertThatThrownBy(fresh::snapshot).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void decoratorRecordsCallsAndLogsReasoning() {
        LlmClientPort router = new LlmClientPort() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> LlmResponse<T> chat(LlmRequest<T> request) {
                return (LlmResponse<T>) new LlmResponse<>(Optional.of("answer"), Optional.of("because"), List.of(),
                        new TokenUsage(10, 5, 3, 0), "astra-reasoning", Duration.ofMillis(800));
            }
        };
        LlmClientPort client = new TelemetryRecordingLlmClient(router, collector, logger);

        String answer = client.chat(request(LlmTier.TIER_1)).requireContent();

        assertThat(answer).isEqualTo("answer");
        assertThat(collector.snapshot().llmCalls()).singleElement()
                .satisfies(call -> assertThat(call.modelId()).isEqualTo("astra-reasoning"));
        assertThat(logger.reasoning).containsExactly("TIER_1|heal:LoginTest|because");
    }

    private static LlmRequest<String> request(LlmTier tier) {
        return LlmRequest.text(tier, MigrationStage.STAGE_3_CODEGEN, "heal:LoginTest", List.of(UserMessage.of("hi")));
    }

    private static LlmResponse<String> response(String model, TokenUsage usage) {
        return new LlmResponse<>(Optional.of("ok"), Optional.empty(), List.of(), usage, model, Duration.ofMillis(5));
    }

    private static ModelPricing pricing(String model, String input, String output) {
        return new ModelPricing(model, new BigDecimal(input), new BigDecimal(output), new BigDecimal(output),
                new BigDecimal("0.5"));
    }

    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    static final class RecordingSink implements TelemetrySinkPort {
        final List<String> appended = new ArrayList<>();
        boolean failing;

        @Override
        public void append(String testClassFqcn, LlmCallRecord call) {
            if (failing) {
                throw new IllegalStateException("disk full");
            }
            appended.add(testClassFqcn + "|" + call.purpose());
        }
    }

    static final class RecordingLogger implements MigrationLoggerPort {
        final List<String> warnings = new ArrayList<>();
        final List<String> reasoning = new ArrayList<>();

        @Override
        public void startSession(String testClassFqcn) {
        }

        @Override
        public void enterStage(MigrationStage stage) {
        }

        @Override
        public void enterTest(TestMethodRef test) {
        }

        @Override
        public void exitTest() {
        }

        @Override
        public void info(String message) {
        }

        @Override
        public void warn(String message) {
            warnings.add(message);
        }

        @Override
        public void error(String message, Throwable cause) {
        }

        @Override
        public void reasoning(LlmTier tier, String purpose, String reasoningText) {
            reasoning.add(tier + "|" + purpose + "|" + reasoningText);
        }

        @Override
        public void endSession() {
        }
    }
}
