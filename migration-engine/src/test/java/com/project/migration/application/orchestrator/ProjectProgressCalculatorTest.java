package com.project.migration.application.orchestrator;

import com.project.migration.domain.model.cache.ScreenCacheEntry;
import com.project.migration.domain.model.cache.ScreenElementMapping;
import com.project.migration.domain.model.cache.TestCacheEntry;
import com.project.migration.domain.model.cache.VerificationStatus;
import com.project.migration.domain.model.code.FieldRef;
import com.project.migration.domain.model.code.IosLocatorStrategy;
import com.project.migration.domain.model.code.TestMethodRef;
import com.project.migration.domain.model.report.ProjectProgress;
import com.project.migration.domain.model.report.TestMigrationStatus;
import com.project.migration.domain.model.screen.ScreenId;
import com.project.migration.domain.model.screen.ScreenState;
import com.project.migration.domain.model.screen.ScreenStateKey;
import com.project.migration.testsupport.BankFixture;
import com.project.migration.testsupport.InMemoryCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectProgressCalculatorTest {

    static final Instant NOW = Instant.parse("2026-10-30T10:00:00Z");

    @TempDir
    Path project;

    @Test
    void totalsComeFromTheCodeAndMigratedCountsFromTheCache() {
        BankFixture.copyTo(project);
        InMemoryCache cache = new InMemoryCache();
        cache.saveTest(new TestCacheEntry(new TestMethodRef("com.bank.tests.CardsTest", "openCardDetails"),
                TestMigrationStatus.STABLE_PASS, NOW));
        cache.saveTest(new TestCacheEntry(new TestMethodRef("com.bank.tests.CardsTest", "backFromCards"),
                TestMigrationStatus.NEEDS_MANUAL_FIX, NOW));
        ScreenId home = new ScreenId("HomePage");
        cache.saveScreen(ScreenCacheEntry.empty(home, NOW)
                .withMapping(new ScreenElementMapping(new FieldRef("com.bank.pages.HomePage", "cardsTile"), "x",
                        IosLocatorStrategy.ACCESSIBILITY_ID, "y", VerificationStatus.VERIFIED_STABLE), NOW)
                .withState(ScreenStateKey.of(home, ScreenState.DEFAULT), VerificationStatus.VERIFIED_STABLE, NOW));
        cache.saveScreen(ScreenCacheEntry.empty(new ScreenId("CardsPage"), NOW));

        ProjectProgress progress = new ProjectProgressCalculator(BankFixture.analyzer(project), cache).calculate();

        assertThat(progress.totalTests()).isEqualTo(BankFixture.analyzer(project).listAllTestMethods().size());
        assertThat(progress.migratedTests()).isEqualTo(1);
        assertThat(progress.totalScreens()).isEqualTo(5);
        assertThat(progress.migratedScreens()).isEqualTo(1);
    }

    @Test
    void migratedCountsNeverExceedTheTotals() {
        BankFixture.copyTo(project);
        InMemoryCache cache = new InMemoryCache();
        for (int i = 0; i < 100; i++) {
            cache.saveTest(new TestCacheEntry(new TestMethodRef("gone.Test", "t" + i), TestMigrationStatus.STABLE_PASS, NOW));
        }

        ProjectProgress progress = new ProjectProgressCalculator(BankFixture.analyzer(project), cache).calculate();

        assertThat(progress.migratedTests()).isEqualTo(100);
        assertThat(progress.totalTests()).isEqualTo(100);
    }
}
