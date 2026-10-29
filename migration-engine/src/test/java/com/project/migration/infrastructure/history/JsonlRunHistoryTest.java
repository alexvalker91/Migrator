package com.project.migration.infrastructure.history;

import com.project.migration.domain.model.history.ClassRunSummary;
import com.project.migration.domain.model.orchestration.MigrationExitStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JsonlRunHistoryTest {

    @TempDir
    Path migrationDir;

    @Test
    void appendsOneLinePerRunAndReadsThemBackInOrder() throws IOException {
        JsonlRunHistory history = new JsonlRunHistory(migrationDir);
        assertThat(history.findAll()).isEmpty();

        history.append(summary("com.bank.LoginTest", 10, "2.50"));
        history.append(summary("com.bank.CardsTest", 4, "0.80"));

        List<ClassRunSummary> runs = history.findAll();
        assertThat(runs).extracting(ClassRunSummary::testClassFqcn).containsExactly("com.bank.LoginTest", "com.bank.CardsTest");
        assertThat(runs.getFirst()).isEqualTo(summary("com.bank.LoginTest", 10, "2.50"));
        assertThat(Files.readAllLines(migrationDir.resolve("history/runs.jsonl"))).hasSize(2)
                .allSatisfy(line -> assertThat(line).contains("\"test_class_fqcn\"").doesNotContain("\n"));
    }

    @Test
    void aDamagedLineIsSkippedNotFatal() throws IOException {
        JsonlRunHistory history = new JsonlRunHistory(migrationDir);
        history.append(summary("com.bank.LoginTest", 10, "2.50"));
        Files.writeString(migrationDir.resolve("history/runs.jsonl"), "{not json\n\n{\"unknown\": 1}\n",
                java.nio.file.StandardOpenOption.APPEND);
        history.append(summary("com.bank.CardsTest", 4, "0.80"));

        assertThat(history.findAll()).extracting(ClassRunSummary::testClassFqcn)
                .containsExactly("com.bank.LoginTest", "com.bank.CardsTest");
    }

    @Test
    void perTestFiguresDivideByTheProcessedTests() {
        ClassRunSummary run = summary("com.bank.LoginTest", 10, "2.50");

        assertThat(run.costPerTest()).isEqualByComparingTo("0.25");
        assertThat(run.tokensPerTest()).isEqualTo(5_000.0);
        assertThat(run.durationPerTest()).isEqualTo(Duration.ofMinutes(3));
        assertThat(summary("x.T", 0, "0").costPerTest()).isEqualByComparingTo("0");
        assertThat(summary("x.T", 0, "0").durationPerTest()).isEqualTo(Duration.ZERO);
    }

    static ClassRunSummary summary(String fqcn, int processed, String cost) {
        return new ClassRunSummary(fqcn, Instant.parse("2026-10-30T10:00:00Z"), MigrationExitStatus.SUCCESS, processed,
                Math.max(processed - 1, 0), Math.min(processed, 1), 0, 2, processed * 5_000L, new BigDecimal(cost), 12_000, new BigDecimal("0.04"), 0.62,
                Duration.ofMinutes(3L * processed), Duration.ofMinutes(2L * processed), Duration.ofMinutes(processed));
    }
}
