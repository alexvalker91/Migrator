package com.project.migration.domain.model.telemetry;

import com.project.migration.domain.model.Checks;

/**
 * Token counters of one or many LLM calls. {@code cachedInput} is the part of {@code input} served from the
 * provider's prompt cache; {@code reasoning} is reported separately from {@code output}.
 */
public record TokenUsage(long input, long output, long reasoning, long cachedInput) {

    public static final TokenUsage ZERO = new TokenUsage(0, 0, 0, 0);

    public TokenUsage {
        Checks.nonNegative(input, "input");
        Checks.nonNegative(output, "output");
        Checks.nonNegative(reasoning, "reasoning");
        Checks.nonNegative(cachedInput, "cachedInput");
        if (cachedInput > input) {
            throw new IllegalArgumentException("cachedInput (" + cachedInput + ") must not exceed input (" + input + ")");
        }
    }

    public TokenUsage plus(TokenUsage other) {
        return new TokenUsage(
                Math.addExact(input, other.input),
                Math.addExact(output, other.output),
                Math.addExact(reasoning, other.reasoning),
                Math.addExact(cachedInput, other.cachedInput));
    }

    public long total() {
        return input + output + reasoning;
    }
}
