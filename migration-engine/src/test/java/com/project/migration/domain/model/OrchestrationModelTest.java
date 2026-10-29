package com.project.migration.domain.model;

import com.project.migration.domain.model.config.GateConfig;
import com.project.migration.domain.model.config.MavenConfig;
import com.project.migration.domain.model.orchestration.MigrationExitStatus;
import com.project.migration.domain.model.orchestration.TestClassMigrationRequest;
import com.project.migration.domain.model.run.TestOutcome;
import com.project.migration.domain.model.run.TestRunResult;
import com.project.migration.domain.model.telemetry.TestTelemetry;
import com.project.migration.domain.model.code.TestMethodRef;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrchestrationModelTest {

    @Test
    void exitStatusesMapToDistinctProcessCodes() {
        assertThat(List.of(MigrationExitStatus.values())).extracting(MigrationExitStatus::exitCode)
                .containsExactly(0, 1, 2, 3);
    }

    @Test
    void requestDefaultsToPushWithoutPublishingRegressions() {
        TestClassMigrationRequest request = TestClassMigrationRequest.of("com.bank.CardsTest");

        assertThat(request.push()).isTrue();
        assertThat(request.pushOnRegression()).isFalse();
    }

    @Test
    void mavenAndGateSettingsRejectNonsense() {
        assertThat(MavenConfig.DEFAULT.extraArgs()).containsExactly("-Pe2e");
        assertThat(GateConfig.DEFAULT.environmentRetries()).isEqualTo(2);
        assertThatThrownBy(() -> new MavenConfig(Optional.empty(), List.of(), Optional.empty(), Duration.ZERO,
                Duration.ofMinutes(1), Duration.ofMinutes(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GateConfig(Duration.ofMinutes(1), 0, 2, 3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GateConfig(Duration.ofMinutes(1), 1, 2, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testRunsCarryFailureArtifactsAndTelemetrySumsTokens() {
        TestRunResult plain = new TestRunResult(new TestMethodRef("a.T", "m"), Platform.IOS, TestOutcome.PASSED,
                Optional.empty(), Duration.ofSeconds(1), Optional.empty());
        TestRunResult withArtifacts = plain.withFailureArtifacts(Optional.of(Path.of("s.png")), Optional.of(Path.of("p.xml")));

        assertThat(plain.failureScreenshot()).isEmpty();
        assertThat(withArtifacts.failureScreenshot()).contains(Path.of("s.png"));
        assertThat(withArtifacts.failurePageSource()).contains(Path.of("p.xml"));
        assertThat(new TestTelemetry(List.of(), Map.of(MigrationStage.STAGE_4_SELF_HEALING, Duration.ofSeconds(3))).totalTokens())
                .isZero();
        assertThat(MigrationStage.STAGE_4_SELF_HEALING.displayName()).isEqualTo("Self-healing");
    }
}
