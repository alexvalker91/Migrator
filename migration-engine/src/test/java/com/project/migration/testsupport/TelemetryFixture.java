package com.project.migration.testsupport;

import com.project.migration.application.telemetry.TelemetryCollector;
import com.project.migration.domain.model.config.EngineConfig;
import com.project.migration.domain.port.MigrationLoggerPort;
import com.project.migration.infrastructure.config.JsonEngineConfigLoader;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * Real {@link TelemetryCollector} over the default engine configuration, with an in-memory sink.
 */
public final class TelemetryFixture {

    private TelemetryFixture() {
    }

    public static EngineConfig config(Path projectRoot) {
        return new JsonEngineConfigLoader().load(projectRoot, false, Optional.empty());
    }

    public static TelemetryCollector collector(EngineConfig config, MigrationLoggerPort logger, String testClass) {
        TelemetryCollector collector = new TelemetryCollector(config,
                Clock.fixed(Instant.parse("2026-10-30T10:00:00Z"), ZoneOffset.UTC), (cls, call) -> { }, logger);
        collector.startSession(testClass);
        return collector;
    }
}
