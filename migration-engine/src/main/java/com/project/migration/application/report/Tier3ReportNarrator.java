package com.project.migration.application.report;

import com.project.migration.application.prompt.PromptTemplates;
import com.project.migration.domain.model.Checks;
import com.project.migration.domain.model.MigrationStage;
import com.project.migration.domain.model.llm.LlmRequest;
import com.project.migration.domain.model.llm.LlmTier;
import com.project.migration.domain.model.llm.SystemMessage;
import com.project.migration.domain.model.llm.UserMessage;
import com.project.migration.domain.model.report.ClassMigrationReport;
import com.project.migration.domain.model.report.HealingAttempt;
import com.project.migration.domain.model.report.TestMethodReport;
import com.project.migration.domain.model.report.TestMigrationStatus;
import com.project.migration.domain.port.LlmClientPort;
import com.project.migration.domain.port.MigrationLoggerPort;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * One cheap Tier-3 call for the report prose: it gets the outcome per test and the engine's own verdicts and
 * diagnoses, and writes a short summary and a next step for every test that needs a manual fix. The prose is
 * bounded and filtered (only known tests, limited length); any failure of the model gives the deterministic text.
 */
public final class Tier3ReportNarrator implements ReportNarrator {

    private static final int MAX_SUMMARY_LINES = 8;
    private static final int MAX_LINE_CHARS = 400;

    private final LlmClientPort llm;
    private final ReportNarrator fallback;
    private final MigrationLoggerPort logger;

    public Tier3ReportNarrator(LlmClientPort llm, ReportNarrator fallback, MigrationLoggerPort logger) {
        this.llm = Checks.notNull(llm, "llm");
        this.fallback = Checks.notNull(fallback, "fallback");
        this.logger = Checks.notNull(logger, "logger");
    }

    @Override
    public ReportNarrative narrate(ClassMigrationReport report) {
        ReportNarrative deterministic = fallback.narrate(report);
        try {
            NarrativeAnswer answer = llm.chat(LlmRequest.structured(LlmTier.TIER_3, MigrationStage.STAGE_5_FINALIZE,
                    "report-narrative:" + report.testClassFqcn(),
                    List.of(new SystemMessage(PromptTemplates.render("report-narrative.system", Map.of())),
                            UserMessage.of(PromptTemplates.render("report-narrative.user", Map.of(
                                    "facts", facts(report, deterministic)))) ), NarrativeAnswer.class)).requireContent();
            return accept(answer, report, deterministic);
        } catch (RuntimeException e) {
            logger.warn("The report prose was not written by the model (" + e.getMessage() + "), using the plain text");
            return deterministic;
        }
    }

    private ReportNarrative accept(NarrativeAnswer answer, ClassMigrationReport report, ReportNarrative deterministic) {
        List<String> summary = (answer.summary() == null ? List.<String>of() : answer.summary()).stream()
                .filter(line -> line != null && !line.isBlank()).map(String::strip).map(Tier3ReportNarrator::limit)
                .limit(MAX_SUMMARY_LINES).toList();
        if (summary.isEmpty()) {
            return deterministic;
        }
        Set<String> needFix = report.tests().stream().filter(t -> t.status() == TestMigrationStatus.NEEDS_MANUAL_FIX)
                .map(t -> t.test().methodName()).collect(Collectors.toSet());
        Map<String, String> steps = new LinkedHashMap<>(deterministic.nextSteps());
        if (answer.nextSteps() != null) {
            for (NarrativeAnswer.NextStep step : answer.nextSteps()) {
                if (step != null && step.test() != null && needFix.contains(step.test()) && step.step() != null
                        && !step.step().isBlank()) {
                    steps.put(step.test(), limit(step.step().strip()));
                }
            }
        }
        return new ReportNarrative(summary, steps, true);
    }

    private static String facts(ClassMigrationReport report, ReportNarrative deterministic) {
        StringBuilder text = new StringBuilder("Class ").append(report.testClassFqcn()).append(", exit status ")
                .append(report.exitStatus()).append("\nFacts:\n");
        deterministic.summary().forEach(line -> text.append("- ").append(line).append('\n'));
        text.append("\nTests:\n");
        for (TestMethodReport test : report.tests()) {
            text.append("- ").append(test.test().methodName()).append(": ").append(test.status()).append(", runs ")
                    .append(test.totalRuns()).append(", healing attempts ").append(test.healingAttempts().size());
            test.failureEvidence().ifPresent(e -> text.append(", verdict: ").append(limit(e.verdict())));
            if (!test.healingAttempts().isEmpty()) {
                HealingAttempt last = test.healingAttempts().getLast();
                text.append(", last diagnosis: ").append(limit(last.diagnosis()));
            }
            text.append('\n');
        }
        return text.toString();
    }

    private static String limit(String text) {
        return text.length() <= MAX_LINE_CHARS ? text : text.substring(0, MAX_LINE_CHARS) + "...";
    }
}
