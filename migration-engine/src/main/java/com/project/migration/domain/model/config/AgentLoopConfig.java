package com.project.migration.domain.model.config;

import com.project.migration.domain.model.Checks;

import java.time.Duration;
import java.util.Set;

/**
 * Limits of one LLM tool-calling run and the MCP tools the model may use.
 *
 * @param maxSteps               tool-calling rounds per run
 * @param maxStepsWithoutProgress consecutive rounds without new progress (a stored snapshot) before the run is
 *                               declared stuck
 * @param maxTokens              total tokens (input + output + reasoning) a run may spend
 * @param toolCallTimeout        limit for one MCP tool call
 * @param maxToolResultChars     longer tool results are cut before they reach the model
 * @param allowedTools           MCP tools exposed to the model; everything else is neither listed nor callable
 */
public record AgentLoopConfig(
        int maxSteps,
        int maxStepsWithoutProgress,
        long maxTokens,
        Duration toolCallTimeout,
        int maxToolResultChars,
        Set<String> allowedTools) {

    public static final Set<String> DEFAULT_ALLOWED_TOOLS = Set.of(
            "appium_find_element", "appium_find_elements", "appium_gesture", "appium_set_value", "appium_get_text",
            "appium_get_page_source", "appium_screenshot", "appium_app_lifecycle", "appium_mobile_press_key",
            "appium_alert", "appium_get_active_element");

    public static final AgentLoopConfig DEFAULT =
            new AgentLoopConfig(30, 8, 150_000, Duration.ofSeconds(60), 12_000, DEFAULT_ALLOWED_TOOLS);

    public AgentLoopConfig {
        Checks.positive(maxSteps, "maxSteps");
        Checks.positive(maxStepsWithoutProgress, "maxStepsWithoutProgress");
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens must be > 0, was " + maxTokens);
        }
        Checks.notNull(toolCallTimeout, "toolCallTimeout");
        if (toolCallTimeout.isZero() || toolCallTimeout.isNegative()) {
            throw new IllegalArgumentException("toolCallTimeout must be positive");
        }
        Checks.positive(maxToolResultChars, "maxToolResultChars");
        allowedTools = Set.copyOf(Checks.notNull(allowedTools, "allowedTools"));
    }

    public boolean isAllowed(String tool) {
        return allowedTools.contains(tool);
    }
}
