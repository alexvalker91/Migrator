package com.project.migration.infrastructure.llm;

import com.project.migration.domain.model.MigrationStage;
import com.project.migration.domain.model.config.EngineConfig;
import com.project.migration.domain.model.llm.LlmRequest;
import com.project.migration.domain.model.llm.LlmResponse;
import com.project.migration.domain.model.llm.LlmTier;
import com.project.migration.domain.model.llm.UserMessage;
import com.project.migration.infrastructure.config.JsonEngineConfigLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One cheap real call through EPAM AI DIAL with the default Tier-3 deployment chain. Runs only when
 * {@code DIAL_API_KEY} is set, so CI and offline builds stay green.
 */
@EnabledIfEnvironmentVariable(named = DialSettings.API_KEY_ENV, matches = ".+")
class DialLiveSmokeTest {

    record Answer(String word) {
    }

    @Test
    void tier3AnswersAStructuredRequest() {
        EngineConfig config = new JsonEngineConfigLoader().load(Path.of("."), false, Optional.empty());
        DialTieredLlmRouter router = new DialTieredLlmRouter(config,
                new DialChatModelFactory(DialSettings.fromEnvironment()),
                new DialTieredLlmRouterTest.RecordingLogger(), Clock.systemUTC());

        LlmResponse<Answer> response = router.chat(LlmRequest.structured(LlmTier.TIER_3,
                MigrationStage.STAGE_1_DELTA_ANALYSIS, "smoke",
                List.of(UserMessage.of("Return the word 'pong' in the field 'word'.")), Answer.class));

        assertThat(response.requireContent().word()).containsIgnoringCase("pong");
        assertThat(response.usage().input()).isPositive();
    }
}
