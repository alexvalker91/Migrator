package com.project.migration.infrastructure.maven;

import com.project.migration.domain.model.Platform;
import com.project.migration.domain.model.code.TestMethodRef;
import com.project.migration.domain.model.config.MavenConfig;
import com.project.migration.domain.model.run.CompilationResult;
import com.project.migration.domain.model.run.TestClassRunResult;
import com.project.migration.domain.model.run.TestOutcome;
import com.project.migration.domain.model.run.TestRunResult;
import com.project.migration.infrastructure.process.CommandExecutor;
import com.project.migration.infrastructure.process.ProcessCommandExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class MavenTestNgRunnerAdapterTest {

    static final TestMethodRef OPEN = new TestMethodRef("com.bank.tests.CardsTest", "openCardDetails");
    static final TestMethodRef BACK = new TestMethodRef("com.bank.tests.CardsTest", "backFromCards");

    @TempDir
    Path root;

    private final List<List<String>> commands = new ArrayList<>();
    private Consumer<Path> onRun = project -> { };
    private CommandExecutor.CommandResult answer = new CommandExecutor.CommandResult(0, "BUILD SUCCESS", false, Duration.ofSeconds(7));
    private MavenTestNgRunnerAdapter adapter;

    @BeforeEach
    void setUp() {
        CommandExecutor fake = (command, workDir, timeout) -> {
            commands.add(command);
            onRun.accept(workDir);
            return answer;
        };
        adapter = new MavenTestNgRunnerAdapter(root, new MavenConfig(Optional.of("mvn"), List.of("-Pe2e"),
                Optional.of("migrator"), Duration.ofMinutes(5), Duration.ofMinutes(15), Duration.ofMinutes(60)),
                fake, Clock.systemUTC());
    }

    @Test
    void passingMethodGivesAPassedRunAndTheStaleResultFileIsGone() throws IOException {
        Path stale = results("<testng-results><suite><test><class name=\"com.bank.tests.CardsTest\">"
                + "<test-method status=\"FAIL\" name=\"openCardDetails\"><exception class=\"X\"><message>old</message>"
                + "</exception></test-method></class></test></suite></testng-results>");
        onRun = project -> {
            assertThat(stale).doesNotExist();
            writeResults("PASS", "openCardDetails");
        };

        TestRunResult result = adapter.runTestMethod(OPEN, Platform.IOS);

        assertThat(result.outcome()).isEqualTo(TestOutcome.PASSED);
        assertThat(result.failure()).isEmpty();
        assertThat(result.testngResultsXml()).isPresent();
        assertThat(commands.getFirst()).contains("-Dtest=com.bank.tests.CardsTest#openCardDetails", "-Dplatform=iOS");
    }

    @Test
    void failingMethodCarriesTheFailureAndTheArtifactsOfThisRunOnly() throws IOException {
        Path target = root.resolve("migrator/target");
        Files.createDirectories(target.resolve("screenshots"));
        Path oldShot = Files.writeString(target.resolve("screenshots/backFromCards_1.png"), "old");
        Files.setLastModifiedTime(oldShot, java.nio.file.attribute.FileTime.fromMillis(1_000));
        onRun = project -> {
            writeResults("FAIL", "backFromCards");
            write(target.resolve("screenshots/backFromCards_2.png"), "new");
            write(target.resolve("page-source/backFromCards_2.xml"), "<hierarchy/>");
            write(target.resolve("page-source/openCardDetails_2.xml"), "<other/>");
        };

        TestRunResult result = adapter.runTestMethod(BACK, Platform.IOS);

        assertThat(result.outcome()).isEqualTo(TestOutcome.FAILED);
        assertThat(result.isHealable()).isTrue();
        assertThat(result.failure().orElseThrow().message()).isEqualTo("boom");
        assertThat(result.failureScreenshot()).hasValueSatisfying(p -> assertThat(p.getFileName().toString()).isEqualTo("backFromCards_2.png"));
        assertThat(result.failurePageSource()).hasValueSatisfying(p -> assertThat(p.getFileName().toString()).isEqualTo("backFromCards_2.xml"));
    }

    @Test
    void aRunWithoutResultFileIsAnErrorWithTheEndOfTheMavenOutput() {
        answer = new CommandExecutor.CommandResult(1, "x".repeat(10_000) + "\n[ERROR] Could not resolve dependencies", false,
                Duration.ofSeconds(3));

        TestRunResult result = adapter.runTestMethod(OPEN, Platform.IOS);

        assertThat(result.outcome()).isEqualTo(TestOutcome.ERROR);
        assertThat(result.isHealable()).isFalse();
        assertThat(result.failure().orElseThrow().message()).contains("no testng-results.xml (exit 1)");
        assertThat(result.failure().orElseThrow().stackTrace()).endsWith("Could not resolve dependencies").hasSizeLessThan(4_100);
    }

    @Test
    void aTimeoutIsAnErrorThatNamesTheLimit() {
        answer = new CommandExecutor.CommandResult(-1, "", true, Duration.ofMinutes(15));

        TestRunResult result = adapter.runTestMethod(OPEN, Platform.IOS);

        assertThat(result.outcome()).isEqualTo(TestOutcome.ERROR);
        assertThat(result.failure().orElseThrow().message()).contains("did not finish within 15 min");
    }

    @Test
    void aFailedBeforeMethodMakesTheTestSkippedAndAMissingMethodAnError() {
        onRun = project -> write(root.resolve("migrator/target/surefire-reports/testng-results.xml"),
                "<testng-results><suite><test><class name=\"com.bank.tests.CardsTest\">"
                        + "<test-method status=\"FAIL\" is-config=\"true\" name=\"restartApp\"><exception class=\"E\">"
                        + "<message>no session</message></exception></test-method></class></test></suite></testng-results>");

        TestRunResult skipped = adapter.runTestMethod(OPEN, Platform.IOS);

        assertThat(skipped.outcome()).isEqualTo(TestOutcome.SKIPPED);
        assertThat(skipped.failure().orElseThrow().message()).isEqualTo("no session");

        onRun = project -> writeResults("PASS", "somethingElse");
        TestRunResult missing = adapter.runTestMethod(OPEN, Platform.IOS);
        assertThat(missing.outcome()).isEqualTo(TestOutcome.ERROR);
        assertThat(missing.failure().orElseThrow().message()).contains("hold no run of");
    }

    @Test
    void classRunReportsEveryMethodOfTheClassWithGroupFilters() {
        onRun = project -> write(root.resolve("migrator/target/surefire-reports/testng-results.xml"),
                TestNgResultsParserTest.RESULTS);

        TestClassRunResult result = adapter.runTestClass("com.bank.tests.CardsTest", Platform.ANDROID);

        assertThat(result.results()).extracting(r -> r.test().methodName() + ":" + r.outcome()).contains(
                "openCardDetails:PASSED", "backFromCards:FAILED", "payWithGooglePay:SKIPPED");
        assertThat(result.results()).noneMatch(r -> r.test().methodName().equals("notRun"));
        assertThat(result.allPassed()).isFalse();
        assertThat(commands.getFirst()).contains("-DexcludedGroups=ios-only", "-Dtest=com.bank.tests.CardsTest");
    }

    @Test
    void classRunWithoutResultsIsOneErrorEntry() {
        answer = new CommandExecutor.CommandResult(1, "boom", false, Duration.ofSeconds(1));

        TestClassRunResult result = adapter.runTestClass("com.bank.tests.CardsTest", Platform.IOS);

        assertThat(result.results()).singleElement().satisfies(r -> {
            assertThat(r.outcome()).isEqualTo(TestOutcome.ERROR);
            assertThat(r.test().methodName()).isEqualTo(MavenTestNgRunnerAdapter.CLASS_RUN_NAME);
        });
        assertThat(result.allPassed()).isFalse();
    }

    @Test
    void compileSucceedsOrReturnsParsedErrorsOrAGenericOne() {
        assertThat(adapter.compile().success()).isTrue();

        answer = new CommandExecutor.CommandResult(1,
                "[ERROR] /w/A.java:[3,1] cannot find symbol\n[ERROR] /w/A.java:[3,1] cannot find symbol", false, Duration.ofSeconds(2));
        CompilationResult parsed = adapter.compile();
        assertThat(parsed.success()).isFalse();
        assertThat(parsed.errors()).singleElement().satisfies(e -> {
            assertThat(e.line()).isEqualTo(3);
            assertThat(e.message()).isEqualTo("cannot find symbol");
        });

        answer = new CommandExecutor.CommandResult(1, "[ERROR] no network", false, Duration.ofSeconds(2));
        assertThat(adapter.compile().errors().getFirst().message()).contains("exit 1").contains("no network");

        answer = new CommandExecutor.CommandResult(-1, "", true, Duration.ofMinutes(5));
        assertThat(adapter.compile().errors().getFirst().message()).contains("did not finish within 5 min");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void worksThroughARealProcessWithAScriptInsteadOfMaven() throws IOException {
        Path script = root.resolve("fake-mvn.sh");
        Files.writeString(script, """
                #!/bin/sh
                dir="%s/migrator/target/surefire-reports"
                mkdir -p "$dir"
                cat > "$dir/testng-results.xml" <<'EOF'
                <testng-results><suite><test><class name="com.bank.tests.CardsTest">
                <test-method status="PASS" name="openCardDetails"/></class></test></suite></testng-results>
                EOF
                echo "args: $@"
                """.formatted(root));
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        MavenTestNgRunnerAdapter real = new MavenTestNgRunnerAdapter(root, new MavenConfig(Optional.of(script.toString()),
                List.of("-Pe2e"), Optional.empty(), Duration.ofMinutes(1), Duration.ofMinutes(1), Duration.ofMinutes(1)),
                new ProcessCommandExecutor(), Clock.systemUTC());

        TestRunResult result = real.runTestMethod(OPEN, Platform.IOS);

        assertThat(result.outcome()).isEqualTo(TestOutcome.PASSED);
    }

    private Path results(String xml) throws IOException {
        Path file = root.resolve("migrator/target/surefire-reports/testng-results.xml");
        Files.createDirectories(file.getParent());
        return Files.writeString(file, xml);
    }

    private void writeResults(String status, String method) {
        String failure = status.equals("FAIL")
                ? "<exception class=\"java.lang.AssertionError\"><message>boom</message>"
                + "<full-stacktrace>trace</full-stacktrace></exception>" : "";
        write(root.resolve("migrator/target/surefire-reports/testng-results.xml"),
                "<testng-results><suite><test><class name=\"com.bank.tests.CardsTest\"><test-method status=\"" + status
                        + "\" name=\"" + method + "\">" + failure + "</test-method></class></test></suite></testng-results>");
    }

    private static void write(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
