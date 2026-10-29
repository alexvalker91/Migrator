package com.project.migration.domain.model.telemetry;

import com.project.migration.domain.model.Checks;
import com.project.migration.domain.model.MigrationStage;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * What one test cost: its LLM calls and the time spent per stage.
 */
public record TestTelemetry(List<LlmCallRecord> calls, Map<MigrationStage, Duration> stageTimings) {

    public TestTelemetry {
        calls = List.copyOf(Checks.notNull(calls, "calls"));
        stageTimings = Map.copyOf(Checks.notNull(stageTimings, "stageTimings"));
    }

    public long totalTokens() {
        return calls.stream().mapToLong(call -> call.usage().total()).sum();
    }
}
