package com.project.migration.infrastructure.telemetry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.project.migration.domain.model.code.CodeNames;
import com.project.migration.domain.model.telemetry.LlmCallRecord;
import com.project.migration.domain.port.TelemetrySinkPort;
import com.project.migration.infrastructure.json.JsonMappers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/**
 * {@link TelemetrySinkPort} appending one JSON object per line to
 * {@code migration_reports/<TestClass>/llm_calls.jsonl}, forced to disk before returning.
 */
public final class JsonlTelemetrySink implements TelemetrySinkPort {

    static final String FILE_NAME = "llm_calls.jsonl";

    /** Line format; {@code snake_case} keys like every other engine file. */
    record CallLine(Instant timestamp, String stage, String tier, String modelId, String purpose, String testMethod,
                    long inputTokens, long outputTokens, long reasoningTokens, long cachedInputTokens, long latencyMs) {
    }

    private final Path reportsDir;
    private final ObjectWriter writer = JsonMappers.persistence().writer().without(SerializationFeature.INDENT_OUTPUT);

    public JsonlTelemetrySink(Path reportsDir) {
        this.reportsDir = reportsDir.toAbsolutePath().normalize();
    }

    @Override
    public synchronized void append(String testClassFqcn, LlmCallRecord call) {
        Path file = reportsDir.resolve(CodeNames.simpleName(testClassFqcn)).resolve(FILE_NAME);
        try {
            Files.createDirectories(file.getParent());
            byte[] line = (writer.writeValueAsString(toLine(call)) + "\n").getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(file,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                ByteBuffer buffer = ByteBuffer.wrap(line);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(false);
            }
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize telemetry record", e);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot append telemetry to " + file, e);
        }
    }

    private static CallLine toLine(LlmCallRecord call) {
        return new CallLine(call.timestamp(), call.stage().name(), call.tier().name(), call.modelId(), call.purpose(),
                call.testMethod().map(Object::toString).orElse(null),
                call.usage().input(), call.usage().output(), call.usage().reasoning(), call.usage().cachedInput(),
                call.latency().toMillis());
    }
}
