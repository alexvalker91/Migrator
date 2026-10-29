package com.project.migration.application.orchestrator;

import com.project.migration.domain.model.Checks;
import com.project.migration.domain.model.cache.ScreenCacheEntry;
import com.project.migration.domain.model.cache.TestCacheEntry;
import com.project.migration.domain.model.cache.VerificationStatus;
import com.project.migration.domain.model.report.ProjectProgress;
import com.project.migration.domain.port.CacheRepositoryPort;
import com.project.migration.domain.port.CodeAnalyzerPort;

/**
 * "N of 1200 tests, M of 50 screens": the totals come from the code, the migrated counts from the cache.
 */
public final class ProjectProgressCalculator {

    private final CodeAnalyzerPort analyzer;
    private final CacheRepositoryPort cache;

    public ProjectProgressCalculator(CodeAnalyzerPort analyzer, CacheRepositoryPort cache) {
        this.analyzer = Checks.notNull(analyzer, "analyzer");
        this.cache = Checks.notNull(cache, "cache");
    }

    public ProjectProgress calculate() {
        int totalTests = analyzer.listAllTestMethods().size();
        int migratedTests = (int) cache.findAllTests().stream().filter(entry -> entry.status().isMigrated())
                .map(TestCacheEntry::test).distinct().count();
        int totalScreens = analyzer.listScreenClasses().size();
        int migratedScreens = (int) cache.findAllScreens().stream()
                .filter(entry -> entry.status() == VerificationStatus.VERIFIED_STABLE)
                .map(ScreenCacheEntry::screen).distinct().count();
        return new ProjectProgress(Math.max(totalTests, migratedTests), Math.min(migratedTests, Math.max(totalTests, migratedTests)),
                Math.max(totalScreens, migratedScreens), Math.min(migratedScreens, Math.max(totalScreens, migratedScreens)));
    }
}
