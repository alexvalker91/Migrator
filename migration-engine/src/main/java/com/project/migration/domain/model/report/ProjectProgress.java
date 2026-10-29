package com.project.migration.domain.model.report;

import com.project.migration.domain.model.Checks;

/**
 * Project-wide migration progress (e.g. 140 / 1200 tests, 12 / 50 screens).
 */
public record ProjectProgress(int totalTests, int migratedTests, int totalScreens, int migratedScreens) {

    public ProjectProgress {
        Checks.nonNegative(totalTests, "totalTests");
        Checks.nonNegative(migratedTests, "migratedTests");
        Checks.nonNegative(totalScreens, "totalScreens");
        Checks.nonNegative(migratedScreens, "migratedScreens");
        if (migratedTests > totalTests || migratedScreens > totalScreens) {
            throw new IllegalArgumentException("migrated count must not exceed total");
        }
    }

    public double testsPercent() {
        return totalTests == 0 ? 0.0 : 100.0 * migratedTests / totalTests;
    }

    public double screensPercent() {
        return totalScreens == 0 ? 0.0 : 100.0 * migratedScreens / totalScreens;
    }
}
