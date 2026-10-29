package com.project.migration.infrastructure.cli;

import com.project.migration.application.orchestrator.ClassMigrator;
import com.project.migration.domain.port.CodeAnalyzerPort;

/**
 * The assembled object graph: what the runner needs and what must be closed at the end.
 */
interface Engine extends AutoCloseable {

    ClassMigrator orchestrator();

    CodeAnalyzerPort analyzer();

    /** Stops MCP servers and closes the session log. Idempotent. */
    @Override
    void close();
}
