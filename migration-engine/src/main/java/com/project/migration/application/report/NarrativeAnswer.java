package com.project.migration.application.report;

import java.util.List;

/**
 * Structured answer of the Tier-3 model for the report prose.
 *
 * @param summary   5 to 8 short lines
 * @param nextSteps one concrete step per test that needs a manual fix
 */
public record NarrativeAnswer(List<String> summary, List<NextStep> nextSteps) {

    /** @param test test method name */
    public record NextStep(String test, String step) {
    }
}
