package com.project.migration.domain.model.telemetry;

import com.project.migration.domain.model.Checks;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * USD price per 1M tokens for a model deployment. Cached input is billed instead of (not on top of) regular
 * input.
 */
public record ModelPricing(
        String modelId,
        BigDecimal inputPer1M,
        BigDecimal outputPer1M,
        BigDecimal reasoningPer1M,
        BigDecimal cachedInputPer1M) {

    private static final BigDecimal ONE_MILLION = BigDecimal.valueOf(1_000_000);
    private static final int COST_SCALE = 6;

    public ModelPricing {
        Checks.notBlank(modelId, "modelId");
        requireNonNegative(inputPer1M, "inputPer1M");
        requireNonNegative(outputPer1M, "outputPer1M");
        requireNonNegative(reasoningPer1M, "reasoningPer1M");
        requireNonNegative(cachedInputPer1M, "cachedInputPer1M");
    }

    public BigDecimal costOf(TokenUsage usage) {
        long uncachedInput = usage.input() - usage.cachedInput();
        BigDecimal perMillion = inputPer1M.multiply(BigDecimal.valueOf(uncachedInput))
                .add(cachedInputPer1M.multiply(BigDecimal.valueOf(usage.cachedInput())))
                .add(outputPer1M.multiply(BigDecimal.valueOf(usage.output())))
                .add(reasoningPer1M.multiply(BigDecimal.valueOf(usage.reasoning())));
        return perMillion.divide(ONE_MILLION, COST_SCALE, RoundingMode.HALF_UP);
    }

    /** Cost of {@code tokens} that would have been sent as regular input (used to price savings). */
    public BigDecimal inputCostOf(long tokens) {
        return inputPer1M.multiply(BigDecimal.valueOf(tokens)).divide(ONE_MILLION, COST_SCALE, RoundingMode.HALF_UP);
    }

    private static void requireNonNegative(BigDecimal value, String name) {
        Checks.notNull(value, name);
        if (value.signum() < 0) {
            throw new IllegalArgumentException(name + " must be >= 0");
        }
    }
}
