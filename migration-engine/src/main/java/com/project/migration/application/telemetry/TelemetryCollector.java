package com.project.migration.application.telemetry;

import com.project.migration.domain.model.Checks;
import com.project.migration.domain.model.MigrationStage;
import com.project.migration.domain.model.code.TestMethodRef;
import com.project.migration.domain.model.config.EngineConfig;
import com.project.migration.domain.model.llm.LlmRequest;
import com.project.migration.domain.model.llm.LlmResponse;
import com.project.migration.domain.model.llm.LlmTier;
import com.project.migration.domain.model.screen.CleaningResult;
import com.project.migration.domain.model.telemetry.CacheStatistics;
import com.project.migration.domain.model.telemetry.LlmCallRecord;
import com.project.migration.domain.model.telemetry.ModelPricing;
import com.project.migration.domain.model.telemetry.SavingsSource;
import com.project.migration.domain.model.telemetry.TelemetrySnapshot;
import com.project.migration.domain.model.telemetry.TestTelemetry;
import com.project.migration.domain.model.telemetry.TokenSavings;
import com.project.migration.domain.port.MigrationLoggerPort;
import com.project.migration.domain.port.TelemetrySinkPort;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Telemetry of one class migration: LLM calls (priced by {@link EngineConfig#pricing()}), token savings,
 * cache hit rate and wall-clock time per stage. Like the session logger it keeps the current stage and test as
 * session state set by the orchestrator. Every LLM call is also appended to the {@link TelemetrySinkPort} at
 * once, so spend survives a crash.
 * <p>
 * Savings are lower-bound estimates, priced at the input price of the Tier-2 model that would have consumed
 * the avoided tokens. Thread-safe.
 */
public final class TelemetryCollector {

    private final Map<String, ModelPricing> pricingByModel;
    private final Optional<ModelPricing> savingsPricing;
    private final Clock clock;
    private final TelemetrySinkPort sink;
    private final MigrationLoggerPort logger;

    private String testClassFqcn;
    private Instant sessionStart;
    private MigrationStage currentStage;
    private Instant stageStart;
    private Optional<TestMethodRef> currentTest = Optional.empty();
    private final List<LlmCallRecord> calls = new ArrayList<>();
    private final List<TokenSavings> savings = new ArrayList<>();
    private final Map<MigrationStage, Duration> stageDurations = new EnumMap<>(MigrationStage.class);
    private final Map<TestMethodRef, Map<MigrationStage, Duration>> testStageDurations = new HashMap<>();
    private final Set<String> unpricedModelsReported = new HashSet<>();
    private long cacheHits;
    private long cacheMisses;

    public TelemetryCollector(EngineConfig config, Clock clock, TelemetrySinkPort sink, MigrationLoggerPort logger) {
        Checks.notNull(config, "config");
        this.pricingByModel = new HashMap<>();
        config.pricing().forEach(pricing -> pricingByModel.put(pricing.modelId(), pricing));
        this.savingsPricing = config.pricingFor(config.modelFor(LlmTier.TIER_2).modelId());
        this.clock = Checks.notNull(clock, "clock");
        this.sink = Checks.notNull(sink, "sink");
        this.logger = Checks.notNull(logger, "logger");
    }

    /** Starts collecting for {@code testClassFqcn}, discarding the previous session. */
    public synchronized void startSession(String testClassFqcn) {
        this.testClassFqcn = Checks.notBlank(testClassFqcn, "testClassFqcn");
        sessionStart = clock.instant();
        currentStage = null;
        stageStart = null;
        currentTest = Optional.empty();
        calls.clear();
        savings.clear();
        stageDurations.clear();
        testStageDurations.clear();
        cacheHits = 0;
        cacheMisses = 0;
    }

    /** Closes the running stage (its time is added up) and starts timing {@code stage}. */
    public synchronized void enterStage(MigrationStage stage) {
        requireSession();
        Checks.notNull(stage, "stage");
        Instant now = clock.instant();
        closeStage(now);
        currentStage = stage;
        stageStart = now;
    }

    public synchronized void enterTest(TestMethodRef test) {
        Checks.notNull(test, "test");
        splitStageClock();
        currentTest = Optional.of(test);
    }

    public synchronized void exitTest() {
        splitStageClock();
        currentTest = Optional.empty();
    }

    /** Time and LLM calls attributed to {@code test} so far (what the report shows per test). */
    public synchronized TestTelemetry testSnapshot(TestMethodRef test) {
        requireSession();
        Checks.notNull(test, "test");
        Map<MigrationStage, Duration> timings = new EnumMap<>(MigrationStage.class);
        timings.putAll(testStageDurations.getOrDefault(test, Map.of()));
        if (currentStage != null && currentTest.filter(test::equals).isPresent()) {
            timings.merge(currentStage, Duration.between(stageStart, clock.instant()), Duration::plus);
        }
        List<LlmCallRecord> testCalls = calls.stream().filter(call -> call.testMethod().filter(test::equals).isPresent()).toList();
        return new TestTelemetry(testCalls, timings);
    }

    /** Books the time of the running stage to the current owner (test or class) before the owner changes. */
    private void splitStageClock() {
        if (currentStage != null) {
            Instant now = clock.instant();
            closeStage(now);
            stageStart = now;
        }
    }

    /** Stops the stage clock; {@link #snapshot()} stays available. */
    public synchronized void finish() {
        requireSession();
        closeStage(clock.instant());
        currentStage = null;
    }

    public void recordLlmCall(LlmRequest<?> request, LlmResponse<?> response) {
        LlmCallRecord call;
        String sessionClass;
        synchronized (this) {
            requireSession();
            call = new LlmCallRecord(clock.instant(), request.stage(), request.tier(), response.modelId(),
                    request.purpose(), response.usage(), response.latency(), currentTest);
            calls.add(call);
            sessionClass = testClassFqcn;
        }
        try {
            sink.append(sessionClass, call);
        } catch (RuntimeException e) {
            logger.warn("Could not persist telemetry of LLM call '" + call.purpose() + "': " + e.getMessage());
        }
    }

    /** Tokens saved by {@code XmlPageSourceCleaner} on one page source. */
    public void recordCleaning(CleaningResult result) {
        recordSavings(SavingsSource.XML_CLEANING, result.savedTokens());
    }

    public synchronized void recordSavings(SavingsSource source, long tokens) {
        requireSession();
        if (tokens > 0) {
            savings.add(new TokenSavings(source, tokens));
        }
    }

    /** A lookup that avoided work (snapshot, verified step or locator reused). */
    public synchronized void cacheHit() {
        cacheHits++;
    }

    /** A lookup that found nothing, so the work had to be done. */
    public synchronized void cacheMiss() {
        cacheMisses++;
    }

    public TelemetrySnapshot snapshot() {
        List<LlmCallRecord> callsCopy;
        List<TokenSavings> savingsCopy;
        Map<MigrationStage, Duration> durations;
        Duration total;
        CacheStatistics cache;
        synchronized (this) {
            requireSession();
            Instant now = clock.instant();
            callsCopy = List.copyOf(calls);
            savingsCopy = List.copyOf(savings);
            durations = new EnumMap<>(stageDurations);
            if (currentStage != null) {
                durations.merge(currentStage, Duration.between(stageStart, now), Duration::plus);
            }
            total = Duration.between(sessionStart, now);
            cache = new CacheStatistics(cacheHits, cacheMisses);
        }
        Map<LlmTier, BigDecimal> costByTier = new EnumMap<>(LlmTier.class);
        for (LlmCallRecord call : callsCopy) {
            costByTier.merge(call.tier(), costOf(call), BigDecimal::add);
        }
        long savedTokens = savingsCopy.stream().mapToLong(TokenSavings::tokens).sum();
        BigDecimal savedCost = savingsPricing.map(pricing -> pricing.inputCostOf(savedTokens)).orElse(BigDecimal.ZERO);
        return new TelemetrySnapshot(callsCopy, savingsCopy, cache, costByTier, savedCost, durations, total);
    }

    private BigDecimal costOf(LlmCallRecord call) {
        ModelPricing pricing = pricingByModel.get(call.modelId());
        if (pricing == null) {
            boolean firstTime;
            synchronized (this) {
                firstTime = unpricedModelsReported.add(call.modelId());
            }
            if (firstTime) {
                logger.warn("No pricing configured for model " + call.modelId() + "; its calls are counted as $0");
            }
            return BigDecimal.ZERO;
        }
        return pricing.costOf(call.usage());
    }

    private void closeStage(Instant now) {
        if (currentStage != null) {
            Duration elapsed = Duration.between(stageStart, now);
            stageDurations.merge(currentStage, elapsed, Duration::plus);
            currentTest.ifPresent(test -> testStageDurations
                    .computeIfAbsent(test, key -> new EnumMap<>(MigrationStage.class))
                    .merge(currentStage, elapsed, Duration::plus));
        }
    }

    private void requireSession() {
        if (testClassFqcn == null) {
            throw new IllegalStateException("startSession() was not called");
        }
    }
}
