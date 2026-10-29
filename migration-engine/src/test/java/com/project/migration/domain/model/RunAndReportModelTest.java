package com.project.migration.domain.model;

import com.project.migration.domain.model.code.MethodRef;
import com.project.migration.domain.model.code.TestMethodRef;
import com.project.migration.domain.model.report.FailureEvidence;
import com.project.migration.domain.model.report.HealingAttempt;
import com.project.migration.domain.model.report.HealingOutcome;
import com.project.migration.domain.model.report.ProjectProgress;
import com.project.migration.domain.model.report.TestMethodReport;
import com.project.migration.domain.model.report.TestMigrationStatus;
import com.project.migration.domain.model.run.TestFailure;
import com.project.migration.domain.model.run.TestOutcome;
import com.project.migration.domain.model.run.TestRunResult;
import com.project.migration.domain.model.vcs.BranchNames;
import com.project.migration.domain.model.vcs.CommitId;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunAndReportModelTest {

    private static final TestMethodRef TEST = new TestMethodRef("webdriverio.LoginTest", "loginNegativeTest");
    private static final TestFailure FAILURE = new TestFailure("NoSuchElement", "at LoginPage.clickLogin");

    @Test
    void onlyGenuineFailuresAreHealable() {
        assertThat(run(TestOutcome.FAILED, Optional.of(FAILURE)).isHealable()).isTrue();
        assertThat(run(TestOutcome.SKIPPED, Optional.empty()).isHealable()).isFalse();
        assertThat(run(TestOutcome.ERROR, Optional.of(FAILURE)).isHealable()).isFalse();
        assertThat(run(TestOutcome.PASSED, Optional.empty()).isPassed()).isTrue();
    }

    @Test
    void runResultEnforcesFailureDetails() {
        assertThatThrownBy(() -> run(TestOutcome.FAILED, Optional.empty())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> run(TestOutcome.PASSED, Optional.of(FAILURE))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testReportStatusMustMatchHealingHistory() {
        HealingAttempt attempt = new HealingAttempt(1, "spinner still visible", Optional.empty(),
                "added waitForLoadingToDisappear()", HealingOutcome.RESOLVED);

        assertThat(report(TestMigrationStatus.HEALED_AND_PASSED, List.of(attempt), Optional.empty()).healingAttempts())
                .containsExactly(attempt);
        assertThatThrownBy(() -> report(TestMigrationStatus.STABLE_PASS, List.of(attempt), Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> report(TestMigrationStatus.HEALED_AND_PASSED, List.of(), Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> report(TestMigrationStatus.NEEDS_MANUAL_FIX, List.of(), Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        FailureEvidence evidence = new FailureEvidence("trace", Optional.empty(), Optional.empty(), "locator changed");
        assertThat(report(TestMigrationStatus.NEEDS_MANUAL_FIX, List.of(), Optional.of(evidence)).failureEvidence())
                .contains(evidence);
    }

    @Test
    void progressPercentages() {
        ProjectProgress progress = new ProjectProgress(1200, 300, 50, 10);

        assertThat(progress.testsPercent()).isEqualTo(25.0);
        assertThat(progress.screensPercent()).isEqualTo(20.0);
        assertThat(new ProjectProgress(0, 0, 0, 0).testsPercent()).isZero();
        assertThatThrownBy(() -> new ProjectProgress(10, 11, 0, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void codeReferencesHaveStableKeys() {
        MethodRef method = MethodRef.of("alex.valker91.page.LoginPage", "enterEmail", "String");

        assertThat(method.key()).isEqualTo("alex.valker91.page.LoginPage#enterEmail(String)");
        assertThat(method.simpleClassName()).isEqualTo("LoginPage");
        assertThat(TEST.key()).isEqualTo("webdriverio.LoginTest#loginNegativeTest");
    }

    @Test
    void branchAndCommitNaming() {
        assertThat(BranchNames.migrationBranch("webdriverio.LoginTest")).isEqualTo("migrate/ios-LoginTest");
        assertThat(BranchNames.codegenCommitMessage("webdriverio.LoginTest")).isEqualTo("codegen: LoginTest");
        assertThat(BranchNames.checkpointCommitMessage("loginNegativeTest", 3))
                .isEqualTo("checkpoint: loginNegativeTest passed 3/3 on iOS");
        assertThat(new CommitId("2d3ff7e0a1").abbreviated()).isEqualTo("2d3ff7e");
        assertThatThrownBy(() -> new CommitId("HEAD")).isInstanceOf(IllegalArgumentException.class);
    }

    private static TestRunResult run(TestOutcome outcome, Optional<TestFailure> failure) {
        return new TestRunResult(TEST, Platform.IOS, outcome, failure, Duration.ofSeconds(30), Optional.empty());
    }

    private static TestMethodReport report(TestMigrationStatus status, List<HealingAttempt> attempts,
                                           Optional<FailureEvidence> evidence) {
        return new TestMethodReport(TEST, status, 3, 4, attempts, evidence, List.of(),
                Map.of(MigrationStage.STAGE_4_STABILIZATION, Duration.ofMinutes(2)));
    }
}
