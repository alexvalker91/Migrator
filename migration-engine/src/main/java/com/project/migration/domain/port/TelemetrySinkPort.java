package com.project.migration.domain.port;

import com.project.migration.domain.model.telemetry.LlmCallRecord;

/**
 * Durable, append-only record of LLM calls ({@code migration_reports/<TestClass>/llm_calls.jsonl}), written as
 * calls happen so the spend of a run that crashes half-way through a class is not lost.
 */
public interface TelemetrySinkPort {

    /**
     * Appends one call; durable when it returns.
     *
     * @param testClassFqcn class under migration the call belongs to
     */
    void append(String testClassFqcn, LlmCallRecord call);
}
