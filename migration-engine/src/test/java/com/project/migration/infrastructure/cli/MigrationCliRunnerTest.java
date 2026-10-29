package com.project.migration.infrastructure.cli;

import com.project.migration.application.orchestrator.ClassMigrator;
import com.project.migration.domain.exception.LlmCallException;
import com.project.migration.domain.model.orchestration.ClassMigrationOutcome;
import com.project.migration.domain.model.orchestration.MigrationExitStatus;
import com.project.migration.domain.model.orchestration.TestClassMigrationRequest;
import com.project.migration.domain.model.report.ReportArtifacts;
import com.project.migration.domain.port.CodeAnalyzerPort;
import com.project.migration.testsupport.BankFixture;
import com.project.migration.testsupport.ReportFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationCliRunnerTest {

    static final String CARDS = "com.bank.tests.CardsTest";
    static final String CLASS_LEVEL = "com.bank.tests.ClassLevelTest";

    @TempDir
    Path project;

    private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    private final Map<String, String> environment = new HashMap<>();
    private final List<TestClassMigrationRequest> requests = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    private Function<TestClassMigrationRequest, MigrationExitStatus> outcomes = request -> MigrationExitStatus.SUCCESS;
    private CodeAnalyzerPort analyzer;

    @BeforeEach
    void setUp() {
        BankFixture.copyTo(project);
        analyzer = BankFixture.analyzer(project);
        environment.put("DIAL_API_KEY", "secret");
        environment.put("MIGRATION_ANDROID_CAPABILITIES", "/caps/android.json");
        environment.put("MIGRATION_IOS_CAPABILITIES", "/caps/ios.json");
    }

    @Test
    void helpPrintsTheUsageAndNeedsNothingElse() {
        environment.clear();

        int code = runner().run(new String[]{"--help"});

        assertThat(code).isZero();
        assertThat(out()).contains("Usage: java -jar").contains("--push-on-regression").contains("Exit codes");
        assertThat(events).isEmpty();
    }

    @Test
    void aWrongCommandLineIsExit64WithTheUsage() {
        assertThat(runner().run(new String[]{})).isEqualTo(64);
        assertThat(err()).contains("Error: no test class given").contains("Usage:");
        assertThat(runner().run(new String[]{"CardsTest", "--nope"})).isEqualTo(64);
        assertThat(events).isEmpty();
    }

    @Test
    void missingEnvironmentVariablesAreNamedBeforeAnyWork() {
        environment.remove("DIAL_API_KEY");
        environment.remove("MIGRATION_IOS_CAPABILITIES");

        int code = runner().run(args("CardsTest"));

        assertThat(code).isEqualTo(64);
        assertThat(err()).contains("environment variable(s) not set: DIAL_API_KEY, MIGRATION_IOS_CAPABILITIES");
        assertThat(events).isEmpty();
    }

    @Test
    void anInvalidConfigFileAndAMissingProjectRootAreExit64() throws IOException {
        Path bad = Files.writeString(project.resolve("bad.json"), "{\"stability_runs\": \"many\"}");

        assertThat(runner().run(args("CardsTest", "--config", bad.toString()))).isEqualTo(64);
        assertThat(err()).contains("Invalid engine config");

        assertThat(runner().run(new String[]{"CardsTest", "--project-root", project.resolve("nope").toString()})).isEqualTo(64);
        assertThat(err()).contains("is not a directory");
        assertThat(events).isEmpty();
    }

    @Test
    void migratesTheClassesInOrderPassesTheFlagsAndReturnsTheHighestExitCode() {
        outcomes = request -> request.testClassFqcn().equals(CARDS) ? MigrationExitStatus.COMPLETED_WITH_MANUAL_FIX : MigrationExitStatus.SUCCESS;

        int code = runner().run(args("CardsTest.java", "ClassLevelTest", "CardsTest", "--no-push", "--push-on-regression"));

        assertThat(code).isEqualTo(1);
        assertThat(requests).containsExactly(new TestClassMigrationRequest(CARDS, false, true),
                new TestClassMigrationRequest(CLASS_LEVEL, false, true));
        assertThat(events).containsExactly("engine created", "engine closed");
        assertThat(out()).contains("== " + CARDS + " : COMPLETED_WITH_MANUAL_FIX (exit 1)").contains(": SUCCESS (exit 0)")
                .contains("Report: dashboard.html").contains("openCardDetails");
    }

    @Test
    void anAbortedClassStopsTheBatch() {
        outcomes = request -> MigrationExitStatus.ABORTED;

        int code = runner().run(args("CardsTest", "ClassLevelTest"));

        assertThat(code).isEqualTo(3);
        assertThat(requests).extracting(TestClassMigrationRequest::testClassFqcn).containsExactly(CARDS);
        assertThat(err()).contains("Stopping: the remaining classes are not started");
    }

    @Test
    void aFailedFinalCheckGivesExit2() {
        outcomes = request -> MigrationExitStatus.STAGE5_FAILED;

        assertThat(runner().run(args("CardsTest"))).isEqualTo(2);
    }

    @Test
    void aClassThatDoesNotResolveIsExit64AndTheEngineIsStillClosed() {
        assertThat(runner().run(args("MissingTest"))).isEqualTo(64);
        assertThat(err()).contains("no test class named 'MissingTest'");
        assertThat(events).containsExactly("engine created", "engine closed");
    }

    @Test
    void infrastructureFailuresAreExit70AndTheEngineIsClosed() {
        ClassMigrator failing = request -> {
            throw new LlmCallException("gateway down");
        };

        assertThat(runnerWith(failing).run(args("CardsTest"))).isEqualTo(70);
        assertThat(err()).contains("Migration infrastructure failure: gateway down");
        assertThat(events).containsExactly("engine created", "engine closed");

        events.clear();
        ClassMigrator crashing = request -> {
            throw new IllegalStateException("bug");
        };
        assertThat(runnerWith(crashing).run(args("CardsTest"))).isEqualTo(70);
        assertThat(err()).contains("Unexpected failure: java.lang.IllegalStateException: bug");
        assertThat(events).containsExactly("engine created", "engine closed");
    }

    @Test
    void aSecondEngineOnTheSameProjectIsRefusedBeforeItIsCreated() throws IOException {
        Path migrationDir = project.resolve(".migration");
        try (ProjectLock held = ProjectLock.acquire(migrationDir, "Other")) {
            assertThat(held).isNotNull();

            assertThat(runner().run(args("CardsTest"))).isEqualTo(64);
        }
        assertThat(err()).contains("already running in this process");
        assertThat(events).isEmpty();
    }

    private MigrationCliRunner runner() {
        return runnerWith(request -> {
            requests.add(request);
            return outcome(request, outcomes.apply(request));
        });
    }

    private MigrationCliRunner runnerWith(ClassMigrator migrator) {
        return new MigrationCliRunner(new PrintStream(outBytes, true, StandardCharsets.UTF_8),
                new PrintStream(errBytes, true, StandardCharsets.UTF_8), environment, project,
                (config, env) -> {
                    events.add("engine created");
                    return new Engine() {
                        @Override
                        public ClassMigrator orchestrator() {
                            return migrator;
                        }

                        @Override
                        public CodeAnalyzerPort analyzer() {
                            return analyzer;
                        }

                        @Override
                        public void close() {
                            events.add("engine closed");
                        }
                    };
                }, false);
    }

    private static ClassMigrationOutcome outcome(TestClassMigrationRequest request, MigrationExitStatus status) {
        var report = ReportFixtures.report(null, status);
        return new ClassMigrationOutcome(status, report, Optional.of(new ReportArtifacts(Path.of("dashboard.html"), Path.of("report.md"))),
                request.push(), "message for " + status);
    }

    private String[] args(String... classes) {
        List<String> all = new ArrayList<>(List.of(classes));
        if (all.stream().noneMatch(a -> a.equals("--project-root"))) {
            all.add("--project-root");
            all.add(project.toString());
        }
        return all.toArray(String[]::new);
    }

    private String out() {
        return outBytes.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return errBytes.toString(StandardCharsets.UTF_8);
    }
}
