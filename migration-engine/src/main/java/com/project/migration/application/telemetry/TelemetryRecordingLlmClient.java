package com.project.migration.application.telemetry;

import com.project.migration.domain.model.Checks;
import com.project.migration.domain.model.llm.LlmRequest;
import com.project.migration.domain.model.llm.LlmResponse;
import com.project.migration.domain.port.LlmClientPort;
import com.project.migration.domain.port.MigrationLoggerPort;

/**
 * {@link LlmClientPort} decorator: every answered call is recorded in the {@link TelemetryCollector} and its
 * reasoning goes to the session log (console only with {@code showReasoning}). Services depend on the port and
 * get telemetry without knowing about it.
 */
public final class TelemetryRecordingLlmClient implements LlmClientPort {

    private final LlmClientPort delegate;
    private final TelemetryCollector telemetry;
    private final MigrationLoggerPort logger;

    public TelemetryRecordingLlmClient(LlmClientPort delegate, TelemetryCollector telemetry, MigrationLoggerPort logger) {
        this.delegate = Checks.notNull(delegate, "delegate");
        this.telemetry = Checks.notNull(telemetry, "telemetry");
        this.logger = Checks.notNull(logger, "logger");
    }

    @Override
    public <T> LlmResponse<T> chat(LlmRequest<T> request) {
        LlmResponse<T> response = delegate.chat(request);
        telemetry.recordLlmCall(request, response);
        response.reasoning().ifPresent(reasoning -> logger.reasoning(request.tier(), request.purpose(), reasoning));
        return response;
    }
}
