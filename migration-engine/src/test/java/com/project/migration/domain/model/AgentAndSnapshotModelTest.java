package com.project.migration.domain.model;

import com.project.migration.domain.model.analysis.MigrationTaskDelta;
import com.project.migration.domain.model.code.BodyInspection;
import com.project.migration.domain.model.codegen.CodeAdaptationResult;
import com.project.migration.domain.model.code.ModificationResult;
import com.project.migration.domain.model.config.AgentLoopConfig;
import com.project.migration.domain.model.config.McpServerConfig;
import com.project.migration.domain.model.analysis.ComplexityMarkerType;
import com.project.migration.domain.model.mcp.McpToolCall;
import com.project.migration.domain.model.screen.ScreenId;
import com.project.migration.domain.model.snapshot.SnapshotCollectionResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentAndSnapshotModelTest {

    @Test
    void agentLoopLimitsMustBePositiveAndToolWhitelistIsExact() {
        assertThat(AgentLoopConfig.DEFAULT.isAllowed("appium_gesture")).isTrue();
        assertThat(AgentLoopConfig.DEFAULT.isAllowed("appium_session_management")).isFalse();
        assertThatThrownBy(() -> new AgentLoopConfig(0, 8, 1, Duration.ofSeconds(1), 10, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentLoopConfig(1, 8, 0, Duration.ofSeconds(1), 10, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentLoopConfig(1, 8, 1, Duration.ZERO, 10, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mcpServerConfigTreatsBlankAppIdAsAbsent() {
        McpServerConfig config = new McpServerConfig("npx", List.of("-y", "appium-mcp"), Map.of(),
                List.of(new McpToolCall("select_device", "{}")), Optional.of(" "), Duration.ofSeconds(5));

        assertThat(config.appId()).isEmpty();
        assertThatThrownBy(() -> new McpServerConfig("npx", List.of(), Map.of(), List.of(), Optional.empty(),
                Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyDeltaIsFullyCachedAndEmptyCollectionIsComplete() {
        MigrationTaskDelta delta = MigrationTaskDelta.empty("com.bank.CardsTest");

        assertThat(delta.isFullyCached()).isTrue();
        assertThat(delta.screenOfClass()).isEmpty();
        assertThat(SnapshotCollectionResult.nothingToDo().isComplete()).isTrue();
        assertThat(new MigrationTaskDelta("a.B", List.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
                Map.of("a.Page", new ScreenId("Page"))).screenOfClass()).containsKey("a.Page");
    }

    @Test
    void bodyInspectionExplainsEveryProblem() {
        BodyInspection fine = new BodyInspection(Optional.empty(), Set.of(), Set.of());
        BodyInspection bad = new BodyInspection(Optional.of("line 1"), Set.of(ComplexityMarkerType.UI_SCROLLABLE),
                Set.of("fly"));

        assertThat(fine.isAcceptable()).isTrue();
        assertThat(bad.isAcceptable()).isFalse();
        assertThat(bad.problems()).contains("does not parse", "UI_SCROLLABLE", "fly");
    }

    @Test
    void adaptationWithoutProblemsHasNoOpenIssues() {
        CodeAdaptationResult clean = new CodeAdaptationResult(new ModificationResult(List.of(), 0), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of());

        assertThat(clean.hasOpenIssues()).isFalse();
    }
}
