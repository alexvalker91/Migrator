package com.project.migration.infrastructure.cli;

import com.project.migration.domain.model.orchestration.ClassMigrationOutcome;
import com.project.migration.domain.model.report.ClassMigrationReport;
import com.project.migration.domain.model.report.TestMethodReport;

import java.io.PrintStream;
import java.util.Locale;

/**
 * What the engine says on the console when a class is done.
 */
final class ConsoleSummary {

    private ConsoleSummary() {
    }

    static void print(PrintStream out, ClassMigrationOutcome outcome) {
        ClassMigrationReport report = outcome.report();
        out.println();
        out.println("== " + report.testClassFqcn() + " : " + outcome.status() + " (exit " + outcome.status().exitCode() + ")");
        out.println(outcome.message());
        if (!report.tests().isEmpty()) {
            int width = report.tests().stream().mapToInt(t -> t.test().methodName().length()).max().orElse(10);
            for (TestMethodReport test : report.tests()) {
                out.println(String.format(Locale.ROOT, "  %-" + width + "s  %-26s runs %d/%d, healing %d", test.test().methodName(),
                        test.status(), test.passStreak(), test.totalRuns(), test.healingAttempts().size()));
            }
        }
        out.println(String.format(Locale.ROOT, "LLM cost $%s, progress %d/%d tests, %d/%d screens%s",
                report.telemetry().totalCost().setScale(2, java.math.RoundingMode.HALF_UP), report.progress().migratedTests(),
                report.progress().totalTests(), report.progress().migratedScreens(), report.progress().totalScreens(),
                outcome.pushed() ? ", branch pushed" : ""));
        outcome.artifacts().ifPresent(artifacts -> out.println("Report: " + artifacts.dashboardHtml()));
    }
}
