package com.project.migration.domain.port;

import com.project.migration.domain.exception.CacheCorruptedException;
import com.project.migration.domain.model.cache.GlobalComponentEntry;
import com.project.migration.domain.model.cache.IosLearnedRule;
import com.project.migration.domain.model.cache.ScreenCacheEntry;
import com.project.migration.domain.model.cache.StepCacheEntry;
import com.project.migration.domain.model.cache.TestCacheEntry;
import com.project.migration.domain.model.code.MethodRef;
import com.project.migration.domain.model.code.TestMethodRef;
import com.project.migration.domain.model.screen.ComponentId;
import com.project.migration.domain.model.screen.ScreenId;

import java.util.List;
import java.util.Optional;

/**
 * Accumulated migration knowledge ({@code .migration/}): index {@code migration_cache.json} plus one shard
 * per screen / step class, so many classes can be migrated without rewriting one large document.
 *
 * <p>Every {@code save*} is atomic (temp file + rename) and durable when it returns. Reads of a file that
 * exists but cannot be parsed throw {@link CacheCorruptedException}; missing entries are {@code Optional.empty()}.</p>
 */
public interface CacheRepositoryPort {

    List<GlobalComponentEntry> findGlobalComponents();

    Optional<GlobalComponentEntry> findGlobalComponent(ComponentId component);

    void saveGlobalComponent(GlobalComponentEntry entry);

    Optional<ScreenCacheEntry> findScreen(ScreenId screen);

    List<ScreenCacheEntry> findAllScreens();

    void saveScreen(ScreenCacheEntry entry);

    Optional<StepCacheEntry> findStep(MethodRef step);

    void saveStep(StepCacheEntry entry);

    Optional<TestCacheEntry> findTest(TestMethodRef test);

    List<TestCacheEntry> findAllTests();

    void saveTest(TestCacheEntry entry);

    List<IosLearnedRule> findLearnedRules();

    /** Adds the rule, replacing an existing rule with the same id. */
    void saveLearnedRule(IosLearnedRule rule);
}
