package com.project.migration.infrastructure.report;

import com.project.migration.application.report.ReportNarrative;
import com.project.migration.domain.model.history.ScalingProjection;
import com.project.migration.domain.model.history.ScalingProjection.Scenario;
import com.project.migration.domain.model.orchestration.MigrationExitStatus;
import com.project.migration.domain.model.report.ClassMigrationReport;
import com.project.migration.domain.model.report.FailureEvidence;
import com.project.migration.domain.model.report.HealingAttempt;
import com.project.migration.domain.model.report.ProjectProgress;
import com.project.migration.domain.model.report.TestMethodReport;
import com.project.migration.domain.model.report.TestMigrationStatus;
import com.project.migration.domain.model.run.TestClassRunResult;
import com.project.migration.domain.model.run.TestRunResult;
import com.project.migration.domain.model.telemetry.TelemetrySnapshot;

import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;

/**
 * The server-rendered part of {@code dashboard.html}: header, summary, KPIs, tables and evidence. Charts are drawn
 * from embedded JSON by the script of the template. Every dynamic string is escaped here.
 */
final class DashboardBodyRenderer {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);

    private final ClassMigrationReport report;
    private final ReportNarrative narrative;
    private final ScalingProjection projection;
    private final ScreenshotEmbedder embedder;
    private final Path reportDir;
    private final StringBuilder html = new StringBuilder(32_768);

    DashboardBodyRenderer(ClassMigrationReport report, ReportNarrative narrative, ScalingProjection projection,
                          ScreenshotEmbedder embedder, Path reportDir) {
        this.report = report;
        this.narrative = narrative;
        this.projection = projection;
        this.embedder = embedder;
        this.reportDir = reportDir;
    }

    String render() {
        header();
        banners();
        summary();
        kpis();
        charts();
        progress();
        testsTable();
        finalChecks();
        projection();
        footer();
        return html.toString();
    }

    private void header() {
        String simple = report.testClassFqcn().substring(report.testClassFqcn().lastIndexOf('.') + 1);
        html.append("<h1>Migration report: ").append(e(simple)).append("</h1>\n<p class=\"text-muted\">")
                .append(e(report.testClassFqcn())).append(" &middot; branch <code>").append(e(report.branch()))
                .append("</code> &middot; ").append(TIME.format(report.startedAt().atOffset(ZoneOffset.UTC))).append(" UTC &middot; ")
                .append(ReportFormat.duration(report.totalDuration())).append(" &middot; ")
                .append(exitBadge(report.exitStatus())).append("</p>\n");
    }

    private void banners() {
        MigrationExitStatus status = report.exitStatus();
        if (status == MigrationExitStatus.ABORTED) {
            html.append("<div class=\"alert alert-error\">The migration was aborted before it could finish. See the log "
                    + "<code>execution.log</code> and the failure evidence below.</div>\n");
        }
        if (report.iosClassRun().isPresent() && !report.iosClassRun().get().allPassed()) {
            html.append("<div class=\"alert alert-error\">The final iOS run of the whole class is red: the migrated tests do not "
                    + "work together.</div>\n");
        }
        if (!report.androidRegressions().isEmpty()) {
            html.append("<div class=\"alert alert-error\">Android regression: the changes to shared Page Objects broke ")
                    .append(report.androidRegressions().stream().map(t -> "<code>" + e(t.methodName()) + "</code>")
                            .reduce((a, b) -> a + ", " + b).orElse("")).append(".</div>\n");
        }
    }

    private void summary() {
        html.append("<div class=\"card mt-4\"><div class=\"card-header\"><div class=\"card-title\">Summary</div><span class=\"text-muted\">")
                .append(narrative.generatedByModel() ? "written by the model from the data below" : "assembled from the data")
                .append("</span></div><div class=\"card-body\"><ul class=\"summary\">");
        narrative.summary().forEach(line -> html.append("<li>").append(e(line)).append("</li>"));
        html.append("</ul></div></div>\n");
    }

    private void kpis() {
        TelemetrySnapshot t = report.telemetry();
        long stable = report.count(TestMigrationStatus.STABLE_PASS);
        long healed = report.count(TestMigrationStatus.HEALED_AND_PASSED);
        long manual = report.count(TestMigrationStatus.NEEDS_MANUAL_FIX);
        long processed = stable + healed + manual;
        int attempts = report.tests().stream().mapToInt(TestMethodReport::healingAttemptCount).sum();
        html.append("<div class=\"stat-grid mt-4\">");
        stat("TESTS MIGRATED", (stable + healed) + " / " + processed, stable + " stable, " + healed + " healed, " + manual + " need a fix");
        stat("TOTAL TIME", ReportFormat.duration(report.totalDuration()),
                processed == 0 ? "no per-test work" : ReportFormat.duration(report.totalDuration().dividedBy(processed)) + " per test");
        stat("LLM TOKENS", ReportFormat.number(t.totalUsage().total()), ReportFormat.number(t.totalUsage().input()) + " in, "
                + ReportFormat.number(t.totalUsage().output()) + " out, " + ReportFormat.number(t.totalUsage().reasoning()) + " reasoning");
        stat("LLM COST", ReportFormat.dollars(t.totalCost()), processed == 0 ? "-" : ReportFormat.dollars(
                t.totalCost().divide(java.math.BigDecimal.valueOf(processed), 4, java.math.RoundingMode.HALF_UP)) + " per test");
        stat("SAVED", ReportFormat.dollars(t.estimatedSavedCost()), ReportFormat.number(t.totalSavedTokens()) + " tokens (XML cleaning + cache)");
        stat("CACHE HIT RATE", ReportFormat.percent(t.cacheStatistics().hitRate()), t.cacheStatistics().hits() + " hits, "
                + t.cacheStatistics().misses() + " misses");
        stat("HEALING ATTEMPTS", String.valueOf(attempts), healed + " tests healed");
        html.append("</div>\n");
    }

    private void stat(String label, String value, String description) {
        html.append("<div class=\"stat-card\"><span class=\"stat-card-label\">").append(e(label)).append("</span><span class=\"stat-card-value\">")
                .append(e(value)).append("</span><span class=\"stat-card-desc\">").append(e(description)).append("</span></div>");
    }

    private void charts() {
        html.append("<div class=\"charts mt-4\">");
        chart("Tokens by tier", "chartTokens");
        chart("Cost by tier ($)", "chartCost");
        chart("Time by stage", "chartStages");
        chart("Tests by status", "chartStatuses");
        chart("Tokens saved", "chartSavings");
        html.append("</div>\n");
    }

    private void chart(String title, String id) {
        html.append("<div class=\"card\"><div class=\"card-header\"><div class=\"card-title\">").append(e(title))
                .append("</div></div><div class=\"card-body\"><div class=\"chart-box\"><canvas id=\"").append(id)
                .append("\"></canvas></div></div></div>");
    }

    private void progress() {
        ProjectProgress p = report.progress();
        html.append("<div class=\"card mt-4\"><div class=\"card-header\"><div class=\"card-title\">Project progress</div></div><div class=\"card-body\">");
        bar("Tests", p.migratedTests(), p.totalTests(), p.testsPercent());
        bar("Screens", p.migratedScreens(), p.totalScreens(), p.screensPercent());
        html.append("</div></div>\n");
    }

    private void bar(String label, int done, int total, double percent) {
        html.append("<div class=\"mb-2\"><div>").append(e(label)).append(": <strong>").append(done).append(" / ").append(total)
                .append("</strong> <span class=\"text-muted\">(").append(String.format(Locale.ROOT, "%.1f%%", percent))
                .append(")</span></div><div class=\"bar\"><span style=\"width:").append(String.format(Locale.ROOT, "%.1f", Math.min(100, percent)))
                .append("%\"></span></div></div>");
    }

    private void testsTable() {
        html.append("<div class=\"card mt-4\"><div class=\"card-header\"><div class=\"card-title\">Tests</div></div><div class=\"card-body\">"
                + "<div class=\"table-wrapper\"><table class=\"table\"><thead><tr><th>Test</th><th>Status</th><th class=\"td-number\">Runs</th>"
                + "<th class=\"td-number\">Healing</th><th class=\"td-number\">Time</th><th class=\"td-number\">Tokens</th></tr></thead><tbody>");
        for (TestMethodReport test : report.tests()) {
            long tokens = test.llmCalls().stream().mapToLong(c -> c.usage().total()).sum();
            html.append("<tr><td>");
            testDetails(test);
            html.append("</td><td>").append(statusBadge(test.status())).append("</td><td class=\"td-number\">")
                    .append(test.passStreak()).append(" in a row / ").append(test.totalRuns()).append("</td><td class=\"td-number\">")
                    .append(test.healingAttempts().size()).append("</td><td class=\"td-number\">")
                    .append(ReportFormat.duration(test.totalDuration())).append("</td><td class=\"td-number\">")
                    .append(ReportFormat.number(tokens)).append("</td></tr>");
        }
        html.append("</tbody></table></div></div></div>\n");
    }

    private void testDetails(TestMethodReport test) {
        String name = test.test().methodName();
        boolean hasDetails = !test.healingAttempts().isEmpty() || test.failureEvidence().isPresent()
                || narrative.nextSteps().containsKey(name);
        if (!hasDetails) {
            html.append("<code>").append(e(name)).append("</code>");
            return;
        }
        html.append("<details><summary><code>").append(e(name)).append("</code></summary><div class=\"detail-body\">");
        String next = narrative.nextSteps().get(name);
        if (next != null) {
            html.append("<div class=\"alert alert-info\"><strong>Next step:</strong> ").append(e(next)).append("</div>");
        }
        for (HealingAttempt attempt : test.healingAttempts()) {
            html.append("<div class=\"attempt\"><div><strong>Attempt ").append(attempt.attemptNumber()).append("</strong> ")
                    .append(attemptBadge(attempt)).append("</div><div>").append(e(attempt.diagnosis())).append("</div><div class=\"text-muted\">")
                    .append(e(attempt.changeSummary())).append("</div>");
            attempt.reasoning().ifPresent(reasoning -> html.append("<details class=\"reasoning\"><summary>[&#129504; LLM REASONING]</summary><pre>")
                    .append(e(reasoning)).append("</pre></details>"));
            html.append("</div>");
        }
        test.failureEvidence().ifPresent(this::evidence);
        html.append("</div></details>");
    }

    private void evidence(FailureEvidence evidence) {
        html.append("<div class=\"evidence\"><div><strong>Verdict:</strong> ").append(e(evidence.verdict())).append("</div>");
        if (!evidence.stackTrace().isBlank()) {
            html.append("<details><summary>Stack trace</summary><pre>").append(e(evidence.stackTrace())).append("</pre></details>");
        }
        evidence.screenshot().ifPresent(shot -> {
            Optional<String> inline = embedder.embed(shot);
            if (inline.isPresent()) {
                html.append("<div><img alt=\"Screenshot at the failure\" src=\"").append(inline.get()).append("\"></div>");
            } else {
                html.append("<div>Screenshot: <a href=\"").append(e(relative(shot))).append("\">").append(e(shot.getFileName().toString()))
                        .append("</a></div>");
            }
        });
        evidence.cleanXml().ifPresent(xml -> html.append("<div>Screen XML: <a href=\"").append(e(relative(xml))).append("\">")
                .append(e(xml.getFileName().toString())).append("</a></div>"));
        html.append("</div>");
    }

    private String relative(Path file) {
        if (!file.isAbsolute()) {
            return file.toString().replace('\\', '/');
        }
        try {
            return reportDir.toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
        } catch (IllegalArgumentException differentRoots) {
            return file.toString();
        }
    }

    private void finalChecks() {
        html.append("<div class=\"card mt-4\"><div class=\"card-header\"><div class=\"card-title\">Final checks</div></div><div class=\"card-body\">");
        runTable("Final iOS run of the class", report.iosClassRun().orElse(null), false);
        runTable("Android regression guard (first run; a method counts as a regression only if it fails again alone)",
                report.androidRegressionRun().orElse(null), true);
        html.append("</div></div>\n");
    }

    private void runTable(String title, TestClassRunResult run, boolean android) {
        html.append("<h3 class=\"mt-2\">").append(e(title)).append("</h3>");
        if (run == null) {
            html.append("<p class=\"text-muted\">Not run.</p>");
            return;
        }
        html.append("<div class=\"table-wrapper\"><table class=\"table\"><thead><tr><th>Test</th><th>Outcome</th></tr></thead><tbody>");
        for (TestRunResult result : run.results()) {
            boolean regression = android && report.androidRegressions().contains(result.test());
            html.append("<tr><td><code>").append(e(result.test().methodName())).append("</code></td><td><span class=\"badge ")
                    .append(result.isPassed() ? "badge-success" : regression ? "badge-error" : "badge-warning")
                    .append("\"><span class=\"badge-dot\"></span>").append(result.outcome()).append(regression ? " (regression)" : "")
                    .append("</span></td></tr>");
        }
        html.append("</tbody></table></div>");
    }

    private void projection() {
        html.append("<div class=\"card mt-4\"><div class=\"card-header\"><div class=\"card-title\">Scaling projection: the rest of the project</div>"
                + "</div><div class=\"card-body\">");
        if (!projection.hasScenarios()) {
            html.append("<div class=\"alert alert-warning\">No finished class runs to extrapolate from yet.</div></div></div>\n");
            return;
        }
        if (projection.lowConfidence()) {
            html.append("<div class=\"alert alert-warning\">Low confidence: the figures rest on only ").append(projection.classesInHistory())
                    .append(" finished class run(s). Treat them as estimates, not forecasts.</div>");
        }
        html.append("<p class=\"text-muted\">").append(projection.remainingTests()).append(" tests still to migrate. Scenarios use the last "
                + "recorded runs: pessimistic = worst run, expected = average weighted by tests, optimistic = best run without the first "
                + "(its cache was empty). Wall-clock time assumes classes run on separate devices.</p>");
        html.append("<div class=\"table-wrapper\"><table class=\"table\"><thead><tr><th>Scenario</th><th class=\"td-number\">$ / test</th>"
                + "<th class=\"td-number\">Tokens / test</th><th class=\"td-number\">Time / test</th><th class=\"td-number\">Total cost</th>"
                + "<th class=\"td-number\">Total tokens</th><th class=\"td-number\">Total time</th><th class=\"td-number\">Wall-clock, 1 / 2 / 4 devices"
                + "</th></tr></thead><tbody>");
        for (Scenario s : projection.scenarios()) {
            html.append("<tr><td>").append(e(s.kind().name().charAt(0) + s.kind().name().substring(1).toLowerCase())).append("</td><td class=\"td-number\">")
                    .append(e(ReportFormat.dollars(s.costPerTest()))).append("</td><td class=\"td-number\">")
                    .append(ReportFormat.number(s.tokensPerTest())).append("</td><td class=\"td-number\">")
                    .append(ReportFormat.duration(s.durationPerTest())).append("</td><td class=\"td-number\">")
                    .append(e(ReportFormat.dollars(s.totalCost()))).append("</td><td class=\"td-number\">")
                    .append(ReportFormat.number(s.totalTokens())).append("</td><td class=\"td-number\">")
                    .append(ReportFormat.duration(s.totalDuration())).append("</td><td class=\"td-number\">")
                    .append(s.wallClock().stream().map(w -> ReportFormat.duration(w.wallClock())).reduce((a, b) -> a + " / " + b).orElse(""))
                    .append("</td></tr>");
        }
        html.append("</tbody></table></div><div class=\"charts mt-4\">");
        chart("Total cost by scenario ($)", "chartProjectionCost");
        chart("Wall-clock hours by scenario and devices", "chartProjectionTime");
        chart("Learning curve: cost per test ($) and cache hit rate (%)", "chartLearning");
        html.append("</div></div></div>\n");
    }

    private void footer() {
        html.append("<p class=\"text-muted mt-4\">Generated ").append(TIME.format(report.finishedAt().atOffset(ZoneOffset.UTC)))
                .append(" UTC by the migration engine. The tokens of writing this report are not included in the figures above; "
                        + "they are in <code>llm_calls.jsonl</code>. Fonts load from the internet when it is available.</p>\n");
    }

    private String exitBadge(MigrationExitStatus status) {
        String css = switch (status) {
            case SUCCESS -> "badge-success";
            case COMPLETED_WITH_MANUAL_FIX -> "badge-warning";
            case STAGE5_FAILED, ABORTED -> "badge-error";
        };
        return "<span class=\"badge " + css + "\"><span class=\"badge-dot\"></span>" + status + "</span>";
    }

    static String statusBadge(TestMigrationStatus status) {
        String css;
        String label;
        switch (status) {
            case STABLE_PASS -> {
                css = "badge-success";
                label = "3/3 STABLE PASS";
            }
            case HEALED_AND_PASSED -> {
                css = "badge-advanced";
                label = "HEALED &amp; PASSED";
            }
            case NEEDS_MANUAL_FIX -> {
                css = "badge-error";
                label = "NEEDS MANUAL FIX";
            }
            case PLATFORM_SPECIFIC -> {
                css = "badge-not-started";
                label = "ANDROID-ONLY";
            }
            case SKIPPED_ALREADY_VERIFIED -> {
                css = "badge-pending";
                label = "ALREADY VERIFIED";
            }
            case IN_PROGRESS -> {
                css = "badge-in-progress";
                label = "IN PROGRESS";
            }
            default -> {
                css = "badge-warning";
                label = "PENDING";
            }
        }
        return "<span class=\"badge " + css + "\"><span class=\"badge-dot\"></span>" + label + "</span>";
    }

    private static String attemptBadge(HealingAttempt attempt) {
        String css = switch (attempt.outcome()) {
            case RESOLVED -> "badge-success";
            case STILL_FAILING -> "badge-warning";
            case COMPILATION_FAILED -> "badge-error";
        };
        return "<span class=\"badge " + css + "\"><span class=\"badge-dot\"></span>" + attempt.outcome() + "</span>";
    }

    private static String e(String text) {
        return HtmlEscaper.html(text);
    }
}
