package com.project.migration.domain.model.screen;

import com.project.migration.domain.model.Checks;

/**
 * Output of {@code XmlPageSourceCleaner}: the compressed XML and token estimates before and after cleaning.
 */
public record CleaningResult(String cleanedXml, long rawTokensEstimate, long cleanedTokensEstimate, long savedTokens) {

    public CleaningResult {
        Checks.notNull(cleanedXml, "cleanedXml");
        Checks.nonNegative(rawTokensEstimate, "rawTokensEstimate");
        Checks.nonNegative(cleanedTokensEstimate, "cleanedTokensEstimate");
        Checks.nonNegative(savedTokens, "savedTokens");
        if (savedTokens != Math.max(0, rawTokensEstimate - cleanedTokensEstimate)) {
            throw new IllegalArgumentException("savedTokens must equal max(0, raw - cleaned)");
        }
    }

    public static CleaningResult of(String cleanedXml, long rawTokensEstimate, long cleanedTokensEstimate) {
        return new CleaningResult(cleanedXml, rawTokensEstimate, cleanedTokensEstimate,
                Math.max(0, rawTokensEstimate - cleanedTokensEstimate));
    }

    /** Raw-to-clean ratio (e.g. {@code 17.5} for 17.5x compression); {@code 1.0} when nothing was produced. */
    public double compressionRatio() {
        return cleanedTokensEstimate == 0 ? 1.0 : (double) rawTokensEstimate / cleanedTokensEstimate;
    }
}
