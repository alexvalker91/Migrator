package com.project.migration.infrastructure.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import com.project.migration.domain.model.MigrationStage;
import com.project.migration.domain.model.code.TestMethodRef;
import com.project.migration.domain.model.llm.LlmTier;
import com.project.migration.domain.model.telemetry.LlmCallRecord;
import com.project.migration.domain.model.telemetry.TokenUsage;
import com.project.migration.infrastructure.json.JsonMappers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class JsonlTelemetrySinkTest {

    @TempDir
    Path reportsDir;

    @Test
    void appendsOneJsonLinePerCallUnderTheClassReportDirectory() throws Exception {
        JsonlTelemetrySink sink = new JsonlTelemetrySink(reportsDir);
        LlmCallRecord call = new LlmCallRecord(Instant.parse("2026-10-29T10:00:00Z"), MigrationStage.STAGE_4_STABILIZATION,
                LlmTier.TIER_1, "claude-opus-5-5", "heal:loginNegativeTest", new TokenUsage(1200, 300, 50, 200),
                Duration.ofMillis(4200), Optional.of(new TestMethodRef("webdriverio.LoginTest", "loginNegativeTest")));

        sink.append("webdriverio.LoginTest", call);
        sink.append("webdriverio.LoginTest", call);

        List<String> lines = Files.readAllLines(reportsDir.resolve("LoginTest/llm_calls.jsonl"));
        assertThat(lines).hasSize(2);
        JsonNode line = JsonMappers.persistence().readTree(lines.getFirst());
        assertThat(line.get("model_id").asText()).isEqualTo("claude-opus-5-5");
        assertThat(line.get("stage").asText()).isEqualTo("STAGE_4_STABILIZATION");
        assertThat(line.get("cached_input_tokens").asLong()).isEqualTo(200);
        assertThat(line.get("latency_ms").asLong()).isEqualTo(4200);
        assertThat(line.get("test_method").asText()).isEqualTo("webdriverio.LoginTest#loginNegativeTest");
        assertThat(line.get("timestamp").asText()).isEqualTo("2026-10-29T10:00:00Z");
    }
}
