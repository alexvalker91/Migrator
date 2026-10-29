package com.project.migration.infrastructure.report;

import com.fasterxml.jackson.core.JacksonException;
import com.project.migration.domain.model.agent.AndroidWalkthrough;
import com.project.migration.domain.model.code.CodeNames;
import com.project.migration.domain.port.WalkthroughSinkPort;
import com.project.migration.infrastructure.fs.AtomicFiles;
import com.project.migration.infrastructure.json.JsonMappers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

/**
 * Writes each walkthrough to {@code migration_reports/<TestClass>/walkthroughs/<method>.json}.
 */
public final class JsonWalkthroughSink implements WalkthroughSinkPort {

    private final Path reportsDir;

    public JsonWalkthroughSink(Path reportsDir) {
        this.reportsDir = reportsDir.toAbsolutePath().normalize();
    }

    @Override
    public void save(String testClassFqcn, AndroidWalkthrough walkthrough) {
        Path file = reportsDir.resolve(CodeNames.simpleName(testClassFqcn)).resolve("walkthroughs")
                .resolve(walkthrough.test().methodName() + ".json");
        try {
            AtomicFiles.writeString(file, JsonMappers.persistence().writeValueAsString(walkthrough) + "\n");
        } catch (JacksonException e) {
            throw new IllegalStateException("Cannot serialize walkthrough of " + walkthrough.test(), e);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + file, e);
        }
    }
}
