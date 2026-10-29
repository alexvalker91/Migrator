package com.project.migration.infrastructure.report;

import com.project.migration.application.report.ReportNarrative;
import com.project.migration.domain.model.history.ScalingProjection;
import com.project.migration.domain.model.report.ClassMigrationReport;
import com.project.migration.domain.model.report.HealingAttempt;
import com.project.migration.domain.model.report.TestMethodReport;
import com.project.migration.domain.model.run.TestClassRunResult;
import com.project.migration.domain.model.run.TestRunResult;
import com.project.migration.domain.model.telemetry.TelemetrySnapshot;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * {@code report.md}: the same facts as the dashboard in plain Markdown, without images (paths only).
 */
final class MarkdownReportWriter {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);

    private MarkdownReportWriter() {
    }

    static String render(ClassMigrationReport report, ReportNarrative narrative, ScalingProjection projection) {
        StringBuilder md = new StringBuilder(8_192);
        String simple = report.testClassFqcn().substring(report.testClassFqcn().lastIndexOf('.') + 1);
        md.append("# Migration report: ").append(simple).append("\n\n`").append(report.testClassFqcn()).append("` on branch `")
                .append(report.branch()).append("`, ").append(TIME.format(report.startedAt().atOffset(ZoneOffset.UTC))).append(" UTC, ")
                .append(ReportFormat.duration(report.totalDuration())).append(". Status: **").append(report.exitStatus()).append("**.\n\n");

        md.append("## Summary\n\n");
        narrative.summary().forEach(line -> md.append("- ").append(line).append('\n'));
        md.append('\n');

        TelemetrySnapshot t = report.telemetry();
        md.append("## Cost and time\n\n| Metric | Value |\n|---|---|\n")
                .append("| LLM tokens | ").append(ReportFormat.number(t.totalUsage().total())).append(" (")
                .append(ReportFormat.number(t.totalUsage().input())).append(" in, ").append(ReportFormat.number(t.totalUsage().output()))
                .append(" out, ").append(ReportFormat.number(t.totalUsage().reasoning())).append(" reasoning) |\n")
                .append("| LLM cost | ").append(ReportFormat.dollars(t.totalCost())).append(" |\n")
                .append("| Saved | ").append(ReportFormat.dollars(t.estimatedSavedCost())).append(", ")
                .append(ReportFormat.number(t.totalSavedTokens())).append(" tokens |\n")
                .append("| Cache hit rate | ").append(ReportFormat.percent(t.cacheStatistics().hitRate())).append(" |\n");
        ReportData.stageGroups(report.stageTimings()).forEach((stage, duration) ->
                md.append("| Time: ").append(stage).append(" | ").append(ReportFormat.duration(duration)).append(" |\n"));
        md.append("\nProject progress: tests ").append(report.progress().migratedTests()).append(" / ").append(report.progress().totalTests())
                .append(String.format(Locale.ROOT, " (%.1f%%)", report.progress().testsPercent())).append(", screens ")
                .append(report.progress().migratedScreens()).append(" / ").append(report.progress().totalScreens())
                .append(String.format(Locale.ROOT, " (%.1f%%)", report.progress().screensPercent())).append(".\n\n");

        md.append("## Tests\n\n| Test | Status | Runs (streak / total) | Healing attempts | Time |\n|---|---|---|---|---|\n");
        for (TestMethodReport test : report.tests()) {
            md.append("| `").append(test.test().methodName()).append("` | ").append(test.status()).append(" | ").append(test.passStreak())
                    .append(" / ").append(test.totalRuns()).append(" | ").append(test.healingAttempts().size()).append(" | ")
                    .append(ReportFormat.duration(test.totalDuration())).append(" |\n");
        }
        for (TestMethodReport test : report.tests()) {
            details(md, test, narrative);
        }

        md.append("\n## Final checks\n\n");
        run(md, "Final iOS run", report.iosClassRun().orElse(null), report);
        run(md, "Android regression guard", report.androidRegressionRun().orElse(null), report);

        md.append("## Scaling projection\n\n");
        if (!projection.hasScenarios()) {
            md.append("No finished class runs to extrapolate from yet.\n\n");
        } else {
            if (projection.lowConfidence()) {
                md.append("> **Low confidence**: based on only ").append(projection.classesInHistory())
                        .append(" finished class run(s); these are estimates.\n\n");
            }
            md.append(projection.remainingTests()).append(" tests still to migrate.\n\n| Scenario | $ / test | Tokens / test | Time / test | Total cost | Total time | "
                    + "Wall-clock 1 / 2 / 4 devices |\n|---|---|---|---|---|---|---|\n");
            for (ScalingProjection.Scenario s : projection.scenarios()) {
                md.append("| ").append(s.kind()).append(" | ").append(ReportFormat.dollars(s.costPerTest())).append(" | ")
                        .append(ReportFormat.number(s.tokensPerTest())).append(" | ").append(ReportFormat.duration(s.durationPerTest()))
                        .append(" | ").append(ReportFormat.dollars(s.totalCost())).append(" | ").append(ReportFormat.duration(s.totalDuration()))
                        .append(" | ").append(s.wallClock().stream().map(w -> ReportFormat.duration(w.wallClock()))
                                .reduce((a, b) -> a + " / " + b).orElse("")).append(" |\n");
            }
            md.append('\n');
        }
        md.append("_The tokens of writing this report are not included above; they are in `llm_calls.jsonl`._\n");
        return md.toString();
    }

    private static void details(StringBuilder md, TestMethodReport test, ReportNarrative narrative) {
        String next = narrative.nextSteps().get(test.test().methodName());
        if (test.healingAttempts().isEmpty() && test.failureEvidence().isEmpty() && next == null) {
            return;
        }
        md.append("\n<details>\n<summary><code>").append(test.test().methodName()).append("</code> - ").append(test.status())
                .append("</summary>\n\n");
        if (next != null) {
            md.append("**Next step:** ").append(next).append("\n\n");
        }
        for (HealingAttempt attempt : test.healingAttempts()) {
            md.append("- Attempt ").append(attempt.attemptNumber()).append(" (").append(attempt.outcome()).append("): ")
                    .append(attempt.diagnosis()).append(" - ").append(attempt.changeSummary()).append('\n');
            attempt.reasoning().ifPresent(reasoning -> md.append("\n  <details><summary>[LLM REASONING]</summary>\n\n  ```\n")
                    .append(indent(reasoning)).append("\n  ```\n  </details>\n\n"));
        }
        test.failureEvidence().ifPresent(evidence -> {
            md.append("\n**Verdict:** ").append(evidence.verdict()).append('\n');
            evidence.screenshot().ifPresent(p -> md.append("\nScreenshot: `").append(p).append("`\n"));
            evidence.cleanXml().ifPresent(p -> md.append("\nScreen XML: `").append(p).append("`\n"));
            if (!evidence.stackTrace().isBlank()) {
                md.append("\n```\n").append(evidence.stackTrace()).append("\n```\n");
            }
        });
        md.append("\n</details>\n");
    }

    private static void run(StringBuilder md, String title, TestClassRunResult run, ClassMigrationReport report) {
        md.append("### ").append(title).append("\n\n");
        if (run == null) {
            md.append("Not run.\n\n");
            return;
        }
        md.append("| Test | Outcome |\n|---|---|\n");
        for (TestRunResult result : run.results()) {
            md.append("| `").append(result.test().methodName()).append("` | ").append(result.outcome())
                    .append(report.androidRegressions().contains(result.test()) ? " (regression)" : "").append(" |\n");
        }
        md.append('\n');
    }

    private static String indent(String text) {
        return text.replace("\n", "\n  ").replace("```", "'''");
    }
}
