package com.project.migration.infrastructure.report;

import com.project.migration.application.report.ProjectionCalculator;
import com.project.migration.application.report.ReportNarrative;
import com.project.migration.application.report.ReportNarrator;
import com.project.migration.domain.model.Checks;
import com.project.migration.domain.model.code.CodeNames;
import com.project.migration.domain.model.history.ScalingProjection;
import com.project.migration.domain.model.report.ClassMigrationReport;
import com.project.migration.domain.model.report.ReportArtifacts;
import com.project.migration.domain.port.ReportGeneratorPort;
import com.project.migration.domain.port.RunHistoryPort;
import com.project.migration.infrastructure.fs.AtomicFiles;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Writes {@code migration_reports/<TestClass>/dashboard.html} (self-contained: design-system CSS, Chart.js and
 * the failure screenshots are inlined) and {@code report.md}. The projection comes from the recorded runs, the prose
 * from the {@link ReportNarrator}. Placeholders of the template are replaced in one pass, so inlined content is
 * never scanned for placeholders again.
 */
public final class HtmlInfographicReportGenerator implements ReportGeneratorPort {

    static final String DASHBOARD_FILE = "dashboard.html";
    static final String MARKDOWN_FILE = "report.md";
    private static final Pattern PLACEHOLDER = Pattern.compile("@@([A-Z_]+)@@|/\\* __CODEMIE_CSS__ \\*/");

    private final Path reportsDir;
    private final RunHistoryPort history;
    private final ReportNarrator narrator;
    private final String template;
    private final String chartJs;
    private final String designCss;

    public HtmlInfographicReportGenerator(Path reportsDir, RunHistoryPort history, ReportNarrator narrator) {
        this.reportsDir = Checks.notNull(reportsDir, "reportsDir").toAbsolutePath().normalize();
        this.history = Checks.notNull(history, "history");
        this.narrator = Checks.notNull(narrator, "narrator");
        this.template = resource("dashboard.html.tpl");
        this.chartJs = resource("chart.umd.min.js");
        this.designCss = resource("codemie-bundle.css");
    }

    @Override
    public ReportArtifacts generate(ClassMigrationReport report) {
        Path directory = reportsDir.resolve(CodeNames.simpleName(report.testClassFqcn()));
        ScalingProjection projection = ProjectionCalculator.project(history.findAll(), report.progress());
        ReportNarrative narrative = narrator.narrate(report);
        String simple = CodeNames.simpleName(report.testClassFqcn());

        String body = new DashboardBodyRenderer(report, narrative, projection,
                new ScreenshotEmbedder(ScreenshotEmbedder.DEFAULT_BUDGET_BYTES), directory).render();
        String html = fill(HtmlEscaper.html("Migration report: " + simple), body, ReportData.json(report, projection));
        String markdown = MarkdownReportWriter.render(report, narrative, projection);

        Path dashboard = directory.resolve(DASHBOARD_FILE);
        Path markdownFile = directory.resolve(MARKDOWN_FILE);
        try {
            AtomicFiles.writeString(dashboard, html);
            AtomicFiles.writeString(markdownFile, markdown);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write the report to " + directory, e);
        }
        return new ReportArtifacts(dashboard, markdownFile);
    }

    private String fill(String title, String body, String dataJson) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder(template.length() + chartJs.length() + designCss.length() + body.length() + 4_096);
        while (matcher.find()) {
            String value = matcher.group(1) == null ? designCss : switch (matcher.group(1)) {
                case "TITLE" -> title;
                case "BODY" -> body;
                case "DATA" -> dataJson;
                case "CHART_JS" -> chartJs;
                default -> throw new IllegalStateException("Unknown placeholder @@" + matcher.group(1) + "@@ in dashboard.html.tpl");
            };
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String resource(String name) {
        String path = "/report/" + name;
        try (InputStream in = HtmlInfographicReportGenerator.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + path, e);
        }
    }
}
