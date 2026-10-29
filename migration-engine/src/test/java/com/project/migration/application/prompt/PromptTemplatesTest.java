package com.project.migration.application.prompt;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PromptTemplatesTest {

    @Test
    void fillsEverySlotAndKeepsDollarsAndBackslashesLiteral() {
        String rendered = PromptTemplates.render("delta-analysis.system", Map.of("states", "A, B$1\\n"));

        assertThat(rendered).contains("one of: A, B$1\\n.").doesNotContain("{{");
    }

    @Test
    void rendersAreStrictAboutSlotsAndTemplates() {
        assertThatThrownBy(() -> PromptTemplates.render("delta-analysis.system", Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("[states]");
        assertThatThrownBy(() -> PromptTemplates.render("delta-analysis.system", Map.of("states", "x", "extra", "y")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PromptTemplates.render("no-such-prompt", Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not found");
    }
}
