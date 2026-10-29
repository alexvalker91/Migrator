package com.project.migration.application.report;

import com.project.migration.domain.model.Checks;

import java.util.List;
import java.util.Map;

/**
 * The prose of a report: a short summary and a next step per test that needs a manual fix. Numbers are never part
 * of the prose contract; the report prints them from the data.
 *
 * @param nextSteps       test method name → what to do next
 * @param generatedByModel written by the LLM (otherwise assembled from the data)
 */
public record ReportNarrative(List<String> summary, Map<String, String> nextSteps, boolean generatedByModel) {

    public ReportNarrative {
        summary = List.copyOf(Checks.notNull(summary, "summary"));
        nextSteps = Map.copyOf(Checks.notNull(nextSteps, "nextSteps"));
    }
}
