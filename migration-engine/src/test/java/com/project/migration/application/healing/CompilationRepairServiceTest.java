package com.project.migration.application.healing;

import com.project.migration.domain.model.config.EngineConfig;
import com.project.migration.domain.model.run.CompilationError;
import com.project.migration.testsupport.BankFixture;
import com.project.migration.testsupport.FakeArtifacts;
import com.project.migration.testsupport.RecordingLogger;
import com.project.migration.testsupport.ScriptedLlm;
import com.project.migration.testsupport.TelemetryFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CompilationRepairServiceTest {

    static final String CARDS = "com.bank.pages.CardsPage";
    static final Path CARDS_FILE = Path.of("app/src/main/java/com/bank/pages/CardsPage.java");

    @TempDir
    Path project;

    private final ScriptedLlm llm = new ScriptedLlm();
    private final FakeArtifacts artifacts = new FakeArtifacts();
    private final RecordingLogger logger = new RecordingLogger();

    private CompilationRepairService service() {
        BankFixture.copyTo(project);
        EngineConfig config = TelemetryFixture.config(project).withFramework(BankFixture.FRAMEWORK);
        var analyzer = BankFixture.analyzer(project);
        analyzer.sourceFileOf(CARDS).ifPresent(file -> artifacts.texts.put(file, "class CardsPage {}"));
        return new CompilationRepairService(analyzer, analyzer.modifier(), llm, artifacts, logger, config);
    }

    private static List<CompilationError> errors() {
        return List.of(new CompilationError(Path.of("/w/app/src/main/java/com/bank/pages/CardsPage.java"), 40, "cannot find symbol"));
    }

    @Test
    void appliesAValidFixAndSendsErrorsAndSourcesToTheModel() throws IOException {
        CompilationRepairService service = service();
        llm.thenReturn(new CompileFix("use the helper", List.of(new HealingProposal.Change(ChangeKind.REPLACE_METHOD_BODY, CARDS,
                "scrollToCard", List.of("String"), null, null, null, null, null, "scrollToElementByText(last4);\nreturn this;", null))));

        Optional<String> fixed = service.repair(errors(), Set.of(CARDS_FILE));

        assertThat(fixed).contains("use the helper");
        assertThat(Files.readString(BankFixture.source(project, CARDS, false))).contains("scrollToElementByText(last4);");
        String prompt = llm.requests().getFirst().messages().toString();
        assertThat(prompt).contains("CardsPage.java:40 cannot find symbol").contains("class CardsPage {}")
                .contains("Allowed classes: " + CARDS);
        assertThat(llm.requests().getFirst().tier().name()).isEqualTo("TIER_1");
    }

    @Test
    void rejectsChangesToFrameworkClassesAndRepeatsOnce() {
        CompilationRepairService service = service();
        HealingProposal.Change forbidden = new HealingProposal.Change(ChangeKind.REPLACE_METHOD_BODY,
                "com.bank.framework.BasePage", "navigateBack", List.of(), null, null, null, null, null, "return;", null);
        llm.thenReturn(new CompileFix("bad", List.of(forbidden))).thenReturn(new CompileFix("still bad", List.of(forbidden)));

        Optional<String> fixed = service.repair(errors(), Set.of(CARDS_FILE, Path.of("app/src/main/java/com/bank/framework/BasePage.java")));

        assertThat(fixed).isEmpty();
        assertThat(llm.requests()).hasSize(2);
        assertThat(llm.requests().get(1).messages().toString()).contains("may not be changed");
    }

    @Test
    void doesNothingWhenNoProjectClassIsInvolved() {
        CompilationRepairService service = service();

        Optional<String> fixed = service.repair(List.of(new CompilationError(Path.of("/w/pom.xml"), 0, "boom")), Set.of());

        assertThat(fixed).isEmpty();
        assertThat(llm.requests()).isEmpty();
        assertThat(logger.contains("No project class to repair")).isTrue();
    }

    @Test
    void mapsSourceFilesToClassNames() {
        assertThat(CompilationRepairService.classOf(Path.of("/w/app/src/test/java/com/bank/tests/CardsTest.java")))
                .contains("com.bank.tests.CardsTest");
        assertThat(CompilationRepairService.classOf(Path.of("pom.xml"))).isEmpty();
        assertThat(CompilationRepairService.classOf(Path.of("README.java.txt"))).isEmpty();
    }
}
