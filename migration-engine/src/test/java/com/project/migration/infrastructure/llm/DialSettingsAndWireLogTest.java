package com.project.migration.infrastructure.llm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DialSettingsAndWireLogTest {

    @TempDir
    Path dir;

    @Test
    void settingsComeFromTheEnvironmentWithTheDefaultHost() {
        DialSettings settings = DialSettings.from(Map.of(DialSettings.API_KEY_ENV, "secret-key"));

        assertThat(settings.deploymentUrl("claude-opus-5-5"))
                .isEqualTo("https://ai-proxy.lab.epam.com/openai/deployments/claude-opus-5-5");
        assertThat(settings.toString()).doesNotContain("secret-key");
        assertThat(DialSettings.from(Map.of(DialSettings.API_KEY_ENV, "k", DialSettings.HOST_ENV, "http://dial/"))
                .deploymentUrl("m")).isEqualTo("http://dial/openai/deployments/m");
    }

    @Test
    void missingKeyFailsFast() {
        assertThatThrownBy(() -> DialSettings.from(Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DIAL_API_KEY");
    }

    @Test
    void wireLogIsOffByDefaultAndRoutedToItsFileWhenEnabled() throws Exception {
        org.slf4j.Logger http = LoggerFactory.getLogger(LlmWireLog.HTTP_LOGGER + ".LoggingHttpClient");
        assertThat(http.isInfoEnabled()).isFalse();

        Path file = dir.resolve("llm_wire.log");
        try (LlmWireLog ignored = LlmWireLog.enable(file)) {
            http.info("HTTP request: POST /openai/deployments/x");
        }
        http.info("after close");

        assertThat(Files.readString(file)).contains("HTTP request").doesNotContain("after close");
        assertThat(http.isInfoEnabled()).isFalse();
    }
}
