package com.project.migration.infrastructure.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.project.migration.application.report.ReportNarrative;
import com.project.migration.domain.model.orchestration.MigrationExitStatus;
import com.project.migration.domain.model.report.ClassMigrationReport;
import com.project.migration.domain.model.report.ReportArtifacts;
import com.project.migration.domain.port.RunHistoryPort;
import com.project.migration.infrastructure.json.JsonMappers;
import com.project.migration.testsupport.ReportFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class HtmlInfographicReportGeneratorTest {

    @TempDir
    Path dir;

    @Test
    void writesASelfContainedDashboardAndAMarkdownReport() throws IOException {
        Path shot = screenshot(1080, 2400);
        ReportArtifacts artifacts = generator(3, narrative()).generate(ReportFixtures.report(shot, MigrationExitStatus.COMPLETED_WITH_MANUAL_FIX));

        String html = Files.readString(artifacts.dashboardHtml());
        assertThat(artifacts.dashboardHtml()).isEqualTo(dir.resolve("reports/CardsTest/dashboard.html").toAbsolutePath().normalize());
        assertThat(artifacts.reportMarkdown()).isEqualTo(dir.resolve("reports/CardsTest/report.md").toAbsolutePath().normalize());
        assertThat(html).startsWith("<!DOCTYPE html>").contains("<title>Migration report: CardsTest</title>")
                .contains("Chart.js v4.4.7").contains("new Chart(el, config)").contains(".stat-card")
                .doesNotContain("@@").doesNotContain("__CODEMIE_CSS__");
        assertThat(html).doesNotContain("<script src=").doesNotContain("<link ").doesNotContain("cdn.jsdelivr");
        assertThat(html).contains("Migration report: CardsTest").contains("badge-warning").contains("COMPLETED_WITH_MANUAL_FIX")
                .contains("3/3 STABLE PASS").contains("HEALED &amp; PASSED").contains("NEEDS MANUAL FIX").contains("ANDROID-ONLY")
                .contains("140 / 1200").contains("12 / 50").contains("Summary").contains("Next step:")
                .contains("[&#129504; LLM REASONING]").contains("Final checks").contains("Scaling projection");
    }

    @Test
    void everyDynamicStringIsEscapedAndTheEmbeddedJsonCannotCloseTheScript() throws IOException {
        ClassMigrationReport report = ReportFixtures.report(null, MigrationExitStatus.SUCCESS);

        String html = Files.readString(generator(3, narrative()).generate(report).dashboardHtml());

        assertThat(html).doesNotContain("<script>alert").contains("&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;&quot;&amp;");
        assertThat(html.split("</script>", -1).length - 1).isEqualTo(3);
        assertThat(html).contains("thinking about &lt;/script&gt; tiles");
    }

    @Test
    void theChartDataIsValidJsonWithTheExpectedSeries() throws IOException {
        String html = Files.readString(generator(4, narrative()).generate(ReportFixtures.report(null, MigrationExitStatus.SUCCESS)).dashboardHtml());

        Matcher matcher = Pattern.compile("const REPORT = (.*?);\\n\\(function", Pattern.DOTALL).matcher(html);
        assertThat(matcher.find()).isTrue();
        JsonNode data = JsonMappers.llm().readTree(matcher.group(1));

        assertThat(data.get("tokens").get("labels")).hasSize(3);
        assertThat(data.get("tokens").get("input").get(1).asLong()).isEqualTo(1_000);
        assertThat(data.get("cost").get("values").get(0).asDouble()).isEqualTo(0.45);
        assertThat(data.get("stages").get("labels").get(4).asText()).isEqualTo("Self-healing");
        assertThat(data.get("stages").get("values").get(4).asLong()).isEqualTo(13 * 60);
        assertThat(data.get("statuses").get("labels")).extracting(JsonNode::asText)
                .contains("STABLE_PASS", "HEALED_AND_PASSED", "NEEDS_MANUAL_FIX", "PLATFORM_SPECIFIC");
        assertThat(data.get("savings").get("values").get(0).asLong()).isEqualTo(120_000);
        assertThat(data.get("learning").get("labels")).hasSize(4);
        assertThat(data.get("projection").get("labels")).hasSize(3);
        assertThat(data.get("projection").get("devices")).hasSize(3);
    }

    @Test
    void theScriptThatDrawsTheChartsIsValidJavaScript() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                new com.project.migration.infrastructure.process.ProcessCommandExecutor()
                        .run(List.of("node", "--version"), dir, java.time.Duration.ofSeconds(10)).succeeded(), "node is not installed");
        String html = Files.readString(generator(4, narrative()).generate(ReportFixtures.report(null, MigrationExitStatus.SUCCESS)).dashboardHtml());
        int start = html.indexOf("const REPORT = ");
        int end = html.indexOf("</script>", start);
        Path script = Files.writeString(dir.resolve("charts.js"), html.substring(start, end));

        var result = new com.project.migration.infrastructure.process.ProcessCommandExecutor()
                .run(List.of("node", "--check", script.toString()), dir, java.time.Duration.ofSeconds(20));

        assertThat(result.output()).isEmpty();
        assertThat(result.succeeded()).isTrue();
    }

    @Test
    void failureScreenshotsAreEmbeddedSmallOrLinkedWhenTheyDoNotFit() throws IOException {
        Path shot = screenshot(1080, 2400);

        String embedded = Files.readString(generator(3, narrative()).generate(ReportFixtures.report(shot, MigrationExitStatus.SUCCESS)).dashboardHtml());
        Matcher matcher = Pattern.compile("src=\"data:image/jpeg;base64,([A-Za-z0-9+/=]+)\"").matcher(embedded);
        assertThat(matcher.find()).isTrue();
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(matcher.group(1))));
        assertThat(image.getWidth()).isEqualTo(ScreenshotEmbedder.MAX_WIDTH);
        assertThat(image.getHeight()).isEqualTo(Math.round(2400 * (640 / 1080f)));

        Path missing = dir.resolve("reports/CardsTest/failures/loginWithOverload/gone.png");
        String linked = Files.readString(generator(3, narrative()).generate(ReportFixtures.report(missing, MigrationExitStatus.SUCCESS)).dashboardHtml());
        assertThat(linked).doesNotContain("data:image/jpeg").contains("href=\"failures/loginWithOverload/gone.png\"");
        assertThat(linked).contains("href=\"failures/loginWithOverload/screen_clean.xml\"");
    }

    @Test
    void theProjectionSaysWhenItCannotBeTrustedOrComputed() throws IOException {
        ClassMigrationReport report = ReportFixtures.report(null, MigrationExitStatus.SUCCESS);

        String low = Files.readString(generator(2, narrative()).generate(report).dashboardHtml());
        String none = Files.readString(generator(0, narrative()).generate(report).dashboardHtml());
        String fine = Files.readString(generator(5, narrative()).generate(report).dashboardHtml());

        assertThat(low).contains("Low confidence").contains("only 2 finished class run(s)").contains("Pessimistic").contains("Optimistic");
        assertThat(none).contains("No finished class runs to extrapolate from yet.").doesNotContain("Pessimistic");
        assertThat(fine).doesNotContain("Low confidence").contains("Expected").contains("1060 tests still to migrate");
    }

    @Test
    void redFinalChecksAndAbortedRunsGetBanners() throws IOException {
        String red = Files.readString(generator(3, narrative()).generate(ReportFixtures.report(null, MigrationExitStatus.STAGE5_FAILED)).dashboardHtml());

        assertThat(red).contains("Android regression: the changes to shared Page Objects broke").contains("<code>loginWithOverload</code>")
                .contains("(regression)").contains("alert-error");

        String aborted = Files.readString(generator(3, narrative()).generate(ReportFixtures.report(null, MigrationExitStatus.ABORTED)).dashboardHtml());
        assertThat(aborted).contains("The migration was aborted");
    }

    @Test
    void theMarkdownReportMirrorsTheDashboardWithoutImages() throws IOException {
        Path shot = screenshot(100, 200);

        String md = Files.readString(generator(3, narrative()).generate(ReportFixtures.report(shot, MigrationExitStatus.COMPLETED_WITH_MANUAL_FIX)).reportMarkdown());

        assertThat(md).startsWith("# Migration report: CardsTest").contains("Status: **COMPLETED_WITH_MANUAL_FIX**")
                .contains("## Summary").contains("- first summary line").contains("## Tests")
                .contains("| `openCardDetails` | STABLE_PASS | 3 / 3 | 0 |").contains("| `backFromCards` | HEALED_AND_PASSED | 3 / 5 | 1 |")
                .contains("<details>").contains("[LLM REASONING]").contains("**Next step:** look at the predicate")
                .contains("## Scaling projection").contains("| EXPECTED |").contains("140 / 1200 (11.7%)")
                .contains("Screenshot: `").doesNotContain("data:image").doesNotContain("![");
    }

    @Test
    void escapingHelpersAndFormatsBehave() {
        assertThat(HtmlEscaper.html("<a href=\"x\">&'</a>")).isEqualTo("&lt;a href=&quot;x&quot;&gt;&amp;&#39;&lt;/a&gt;");
        assertThat(HtmlEscaper.html(null)).isEmpty();
        assertThat(HtmlEscaper.jsonInScript("{\"a\":\"</script><!--\"}")).doesNotContain("<").doesNotContain(">");
        assertThat(ReportFormat.duration(java.time.Duration.ofSeconds(3725))).isEqualTo("1h 02m");
        assertThat(ReportFormat.duration(java.time.Duration.ofSeconds(125))).isEqualTo("2m 05s");
        assertThat(ReportFormat.duration(java.time.Duration.ofSeconds(9))).isEqualTo("9s");
        assertThat(ReportFormat.dollars(new java.math.BigDecimal("1.2345"))).isEqualTo("$1.23");
        assertThat(ReportFormat.dollars(new java.math.BigDecimal("0.00345"))).isEqualTo("$0.0035");
        assertThat(ReportFormat.number(1234567L)).isEqualTo("1 234 567");
        assertThat(ReportFormat.percent(0.456)).isEqualTo("46%");
    }

    @Test
    void theEmbedderStopsAtItsBudgetAndSkipsFilesThatAreNotImages() throws IOException {
        Path shot = screenshot(640, 1000);
        ScreenshotEmbedder tiny = new ScreenshotEmbedder(100);
        ScreenshotEmbedder roomy = new ScreenshotEmbedder(ScreenshotEmbedder.DEFAULT_BUDGET_BYTES);
        Path notAnImage = Files.writeString(dir.resolve("x.png"), "not a png");

        assertThat(tiny.embed(shot)).isEmpty();
        assertThat(roomy.embed(shot)).hasValueSatisfying(uri -> assertThat(uri).startsWith("data:image/jpeg;base64,"));
        assertThat(roomy.embed(notAnImage)).isEmpty();
        assertThat(roomy.embed(dir.resolve("missing.png"))).isEmpty();
    }

    private HtmlInfographicReportGenerator generator(int historyRuns, ReportNarrative narrative) {
        RunHistoryPort history = new RunHistoryPort() {
            @Override
            public void append(com.project.migration.domain.model.history.ClassRunSummary summary) {
            }

            @Override
            public List<com.project.migration.domain.model.history.ClassRunSummary> findAll() {
                return ReportFixtures.history(historyRuns);
            }
        };
        return new HtmlInfographicReportGenerator(dir.resolve("reports"), history, report -> narrative);
    }

    private static ReportNarrative narrative() {
        return new ReportNarrative(List.of("first summary line", "second summary line"),
                Map.of("loginWithOverload", "look at the predicate of the email field"), true);
    }

    private Path screenshot(int width, int height) throws IOException {
        Path file = dir.resolve("reports/CardsTest/failures/loginWithOverload/shot.png");
        Files.createDirectories(file.getParent());
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y += 20) {
            for (int x = 0; x < width; x += 20) {
                image.setRGB(x, y, 0xFF0000);
            }
        }
        ImageIO.write(image, "png", file.toFile());
        return file;
    }
}
