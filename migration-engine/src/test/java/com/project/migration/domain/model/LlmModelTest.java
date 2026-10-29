package com.project.migration.domain.model;

import com.project.migration.domain.model.llm.AssistantMessage;
import com.project.migration.domain.model.llm.LlmMessage;
import com.project.migration.domain.model.llm.LlmRequest;
import com.project.migration.domain.model.llm.LlmResponse;
import com.project.migration.domain.model.llm.LlmTier;
import com.project.migration.domain.model.llm.SystemMessage;
import com.project.migration.domain.model.llm.TierModelConfig;
import com.project.migration.domain.model.llm.ToolCall;
import com.project.migration.domain.model.llm.ToolResultMessage;
import com.project.migration.domain.model.llm.ToolSpec;
import com.project.migration.domain.model.llm.UserMessage;
import com.project.migration.domain.model.media.ImageAttachment;
import com.project.migration.domain.model.telemetry.TokenUsage;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmModelTest {

    record LocatorAnswer(String iosLocator) {
    }

    @Test
    void imageAttachmentIsDefensivelyCopiedAndComparedByContent() {
        byte[] bytes = {1, 2, 3};
        ImageAttachment image = ImageAttachment.png(bytes);
        bytes[0] = 9;
        image.data()[1] = 9;

        assertThat(image.data()).containsExactly(1, 2, 3);
        assertThat(image).isEqualTo(ImageAttachment.png(new byte[]{1, 2, 3}));
        assertThat(image.hashCode()).isEqualTo(ImageAttachment.png(new byte[]{1, 2, 3}).hashCode());
    }

    @Test
    void requestDetectsImagesAndStructuredOutput() {
        LlmRequest<LocatorAnswer> request = LlmRequest.structured(LlmTier.TIER_1, MigrationStage.STAGE_4_STABILIZATION,
                "heal", List.of(new SystemMessage("You heal tests"),
                        UserMessage.withImages("Why did it fail?", ImageAttachment.png(new byte[]{1}))),
                LocatorAnswer.class);

        assertThat(request.hasImages()).isTrue();
        assertThat(request.isStructured()).isTrue();
        assertThat(request.reasoningRequested()).isFalse();
        assertThat(request.withReasoning(true).reasoningRequested()).isTrue();
    }

    @Test
    void requestRejectsInvalidConversations() {
        List<LlmMessage> onlySystem = List.of(new SystemMessage("system"));
        assertThatThrownBy(() -> LlmRequest.text(LlmTier.TIER_3, MigrationStage.STAGE_1_DELTA_ANALYSIS, "delta", onlySystem))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LlmRequest.text(LlmTier.TIER_3, MigrationStage.STAGE_1_DELTA_ANALYSIS, "delta", List.of()))
                .isInstanceOf(IllegalArgumentException.class);

        ToolSpec tool = new ToolSpec("appium_find_element", "find", "{\"type\":\"object\"}");
        LlmRequest<String> request = LlmRequest.text(LlmTier.TIER_2, MigrationStage.STAGE_2_SNAPSHOTS, "navigate",
                List.of(UserMessage.of("open login")));
        assertThatThrownBy(() -> request.withTools(List.of(tool, tool))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requestRejectsNonRecordResponseTypes() {
        assertThatThrownBy(() -> LlmRequest.structured(LlmTier.TIER_2, MigrationStage.STAGE_3_CODEGEN, "map",
                List.of(UserMessage.of("map")), Integer.class))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toolLoopExtendsConversationWithoutChangingSettings() {
        LlmRequest<String> first = LlmRequest.text(LlmTier.TIER_2, MigrationStage.STAGE_2_SNAPSHOTS, "navigate",
                List.of(UserMessage.of("open login"))).withReasoning(true);
        ToolCall call = new ToolCall("call-1", "appium_gesture", "{}");

        LlmRequest<String> next = first.continuedWith(List.of(
                AssistantMessage.ofToolCalls(List.of(call)), new ToolResultMessage("call-1", "appium_gesture", "ok")));

        assertThat(next.messages()).hasSize(3);
        assertThat(next.reasoningRequested()).isTrue();
        assertThat(first.messages()).hasSize(1);
    }

    @Test
    void toolOnlyResponseHasNoContent() {
        LlmResponse<String> response = new LlmResponse<>(Optional.empty(), Optional.of("  "),
                List.of(new ToolCall("c1", "appium_get_page_source", "{}")), TokenUsage.ZERO, "gpt-4o", Duration.ZERO);

        assertThat(response.hasToolCalls()).isTrue();
        assertThat(response.reasoning()).isEmpty();
        assertThatThrownBy(response::requireContent).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> new LlmResponse<>(Optional.empty(), Optional.empty(), List.of(), TokenUsage.ZERO,
                "gpt-4o", Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tierFallsBackToBaseModelWithoutReasoningDeployment() {
        TierModelConfig withReasoning = new TierModelConfig(LlmTier.TIER_1, "base", Optional.of("base-reasoning"), true);
        TierModelConfig withoutReasoning = new TierModelConfig(LlmTier.TIER_2, "mid", Optional.empty(), false);

        assertThat(withReasoning.resolveModelId(true)).isEqualTo("base-reasoning");
        assertThat(withReasoning.resolveModelId(false)).isEqualTo("base");
        assertThat(withoutReasoning.resolveModelId(true)).isEqualTo("mid");
        assertThat(withoutReasoning.fallsBackFromReasoning(true)).isTrue();
        assertThat(withReasoning.fallsBackFromReasoning(true)).isFalse();
    }

    @Test
    void deploymentChainTriesReasoningAlternativesThenBaseChain() {
        TierModelConfig tier = new TierModelConfig(LlmTier.TIER_1, "opus", Optional.of("astra-reasoning"), true,
                List.of("sol", "opus@default"), List.of("sol-reasoning"));

        assertThat(tier.deploymentChain(true))
                .containsExactly("astra-reasoning", "sol-reasoning", "opus", "sol", "opus@default");
        assertThat(tier.deploymentChain(false)).containsExactly("opus", "sol", "opus@default");
        assertThat(tier.allModelIds()).hasSize(5);
    }

    @Test
    void reasoningFallbacksRequireAReasoningDeployment() {
        assertThatThrownBy(() -> new TierModelConfig(LlmTier.TIER_3, "haiku", Optional.empty(), true,
                List.of(), List.of("nano-reasoning")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reasoningModelId");
    }
}
