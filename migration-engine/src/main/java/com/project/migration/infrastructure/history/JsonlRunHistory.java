package com.project.migration.infrastructure.history;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.project.migration.domain.model.Checks;
import com.project.migration.domain.model.history.ClassRunSummary;
import com.project.migration.domain.model.orchestration.MigrationExitStatus;
import com.project.migration.domain.port.RunHistoryPort;
import com.project.migration.infrastructure.fs.DirectoryLock;
import com.project.migration.infrastructure.json.JsonMappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link RunHistoryPort} as one JSON object per line in {@code <migrationDir>/history/runs.jsonl}, forced to disk
 * before {@code append} returns. Appends from several engine processes are serialized with a {@link DirectoryLock}.
 * The history only feeds a projection, so a damaged line is skipped with a warning instead of stopping a migration.
 */
public final class JsonlRunHistory implements RunHistoryPort {

    static final String FILE_NAME = "runs.jsonl";
    private static final Logger LOG = LoggerFactory.getLogger(JsonlRunHistory.class);

    /** Line format: durations in milliseconds, {@code snake_case} keys like every other engine file. */
    record Line(String testClassFqcn, Instant finishedAt, MigrationExitStatus status, int processedTests, int stableTests,
                int healedTests, int manualFixTests, int healingAttempts, long totalTokens, BigDecimal cost,
                long savedTokens, BigDecimal savedCost, double cacheHitRate, long durationMs, long runsDurationMs,
                long healingDurationMs) {
    }

    private final Path file;
    private final DirectoryLock lock;
    private final ObjectWriter writer = JsonMappers.persistence().writer().without(SerializationFeature.INDENT_OUTPUT);
    private final ObjectReader reader = JsonMappers.persistence().readerFor(Line.class);

    public JsonlRunHistory(Path migrationDir) {
        Path directory = Checks.notNull(migrationDir, "migrationDir").toAbsolutePath().normalize().resolve("history");
        this.file = directory.resolve(FILE_NAME);
        this.lock = new DirectoryLock(directory);
    }

    @Override
    public void append(ClassRunSummary summary) {
        byte[] bytes;
        try {
            bytes = (writer.writeValueAsString(toLine(summary)) + "\n").getBytes(StandardCharsets.UTF_8);
        } catch (JacksonException e) {
            throw new IllegalStateException("Cannot serialize the run summary of " + summary.testClassFqcn(), e);
        }
        lock.withLock(() -> {
            try {
                Files.createDirectories(file.getParent());
                try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                        StandardOpenOption.APPEND)) {
                    ByteBuffer buffer = ByteBuffer.wrap(bytes);
                    while (buffer.hasRemaining()) {
                        channel.write(buffer);
                    }
                    channel.force(false);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot append to " + file, e);
            }
        });
    }

    @Override
    public List<ClassRunSummary> findAll() {
        if (!Files.exists(file)) {
            return List.of();
        }
        List<ClassRunSummary> runs = new ArrayList<>();
        try {
            int number = 0;
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                number++;
                if (line.isBlank()) {
                    continue;
                }
                try {
                    runs.add(fromLine(reader.readValue(line)));
                } catch (JacksonException | IllegalArgumentException | NullPointerException e) {
                    LOG.warn("Skipping unreadable line {} of {}: {}", number, file, e.getMessage());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
        return List.copyOf(runs);
    }

    private static Line toLine(ClassRunSummary s) {
        return new Line(s.testClassFqcn(), s.finishedAt(), s.status(), s.processedTests(), s.stableTests(), s.healedTests(),
                s.manualFixTests(), s.healingAttempts(), s.totalTokens(), s.cost(), s.savedTokens(), s.savedCost(),
                s.cacheHitRate(), s.duration().toMillis(), s.runsDuration().toMillis(), s.healingDuration().toMillis());
    }

    private static ClassRunSummary fromLine(Line l) {
        return new ClassRunSummary(l.testClassFqcn(), l.finishedAt(), l.status(), l.processedTests(), l.stableTests(),
                l.healedTests(), l.manualFixTests(), l.healingAttempts(), l.totalTokens(), l.cost(), l.savedTokens(),
                l.savedCost(), l.cacheHitRate(), Duration.ofMillis(l.durationMs()), Duration.ofMillis(l.runsDurationMs()),
                Duration.ofMillis(l.healingDurationMs()));
    }
}
