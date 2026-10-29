package com.project.migration.application.report;

import com.project.migration.domain.exception.LlmCallException;
import com.project.migration.domain.model.llm.LlmTier;
import com.project.migration.domain.model.orchestration.MigrationExitStatus;
import com.project.migration.domain.model.report.ClassMigrationReport;
import com.project.migration.testsupport.RecordingLogger;
import com.project.migration.testsupport.ReportFixtures;
import com.project.migration.testsupport.ScriptedLlm;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReportNarratorsTest {

    private final ClassMigrationReport report = ReportFixtures.report(null, MigrationExitStatus.COMPLETED_WITH_MANUAL_FIX);
    private final RecordingLogger logger = new RecordingLogger();

    @Test
    void theDeterministicProseStatesTheOutcomeFromTheDataAndANextStepPerBrokenTest() {
        ReportNarrative narrative = new DeterministicNarrator().narrate(report);

        assertThat(narrative.generatedByModel()).isFalse();
        assertThat(narrative.summary()).hasSize(3);
        assertThat(narrative.summary().get(0)).contains("2 of 3 tests were migrated now: 1 stable, 1 after self-healing, 1 need a manual fix");
        assertThat(narrative.summary().get(1)).contains("Final iOS run of the class: green").contains("Android regression guard: no regressions");
        assertThat(narrative.summary().get(2)).contains("$0.51").contains("128000 tokens saved")
                .contains("cache hit rate 70%");
        assertThat(narrative.nextSteps()).containsOnlyKeys("loginWithOverload");
        assertThat(narrative.nextSteps().get("loginWithOverload")).startsWith("Open the failure evidence and fix it by hand: self-healing used all 3 attempts");
    }

    @Test
    void theModelProseIsBoundedFilteredAndMergedOverTheDeterministicOne() {
        ScriptedLlm llm = new ScriptedLlm().thenReturn(new NarrativeAnswer(
                List.of("Line 1", " ", "x".repeat(900), "L3", "L4", "L5", "L6", "L7", "L8", "L9", "L10"),
                List.of(new NarrativeAnswer.NextStep("loginWithOverload", "Check the email field predicate first."),
                        new NarrativeAnswer.NextStep("openCardDetails", "should be ignored: the test passed"),
                        new NarrativeAnswer.NextStep("ghost", "ignored"))));

        ReportNarrative narrative = new Tier3ReportNarrator(llm, new DeterministicNarrator(), logger).narrate(report);

        assertThat(narrative.generatedByModel()).isTrue();
        assertThat(narrative.summary()).hasSize(8);
        assertThat(narrative.summary().get(1)).hasSize(403).endsWith("...");
        assertThat(narrative.nextSteps()).containsOnlyKeys("loginWithOverload")
                .containsEntry("loginWithOverload", "Check the email field predicate first.");
        assertThat(llm.requests().getFirst().tier()).isEqualTo(LlmTier.TIER_3);
        assertThat(llm.requests().getFirst().purpose()).isEqualTo("report-narrative:com.bank.tests.CardsTest");
        String prompt = llm.requests().getFirst().messages().toString();
        assertThat(prompt).contains("loginWithOverload: NEEDS_MANUAL_FIX").contains("verdict: self-healing used all 3 attempts")
                .contains("exit status COMPLETED_WITH_MANUAL_FIX");
    }

    @Test
    void aFailingOrEmptyModelAnswerFallsBackToThePlainProse() {
        ScriptedLlm failing = new ScriptedLlm().then(request -> {
            throw new LlmCallException("gateway down");
        });
        ScriptedLlm empty = new ScriptedLlm().thenReturn(new NarrativeAnswer(List.of(" "), null));

        ReportNarrative fromFailure = new Tier3ReportNarrator(failing, new DeterministicNarrator(), logger).narrate(report);
        ReportNarrative fromEmpty = new Tier3ReportNarrator(empty, new DeterministicNarrator(), logger).narrate(report);

        assertThat(fromFailure.generatedByModel()).isFalse();
        assertThat(fromFailure.summary()).isEqualTo(new DeterministicNarrator().narrate(report).summary());
        assertThat(fromEmpty.generatedByModel()).isFalse();
        assertThat(logger.contains("gateway down")).isTrue();
    }
}
