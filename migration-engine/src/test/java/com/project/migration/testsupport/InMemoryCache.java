package com.project.migration.testsupport;

import com.project.migration.domain.model.cache.GlobalComponentEntry;
import com.project.migration.domain.model.cache.IosLearnedRule;
import com.project.migration.domain.model.cache.ScreenCacheEntry;
import com.project.migration.domain.model.cache.StepCacheEntry;
import com.project.migration.domain.model.cache.TestCacheEntry;
import com.project.migration.domain.model.code.MethodRef;
import com.project.migration.domain.model.code.TestMethodRef;
import com.project.migration.domain.model.screen.ComponentId;
import com.project.migration.domain.model.screen.ScreenId;
import com.project.migration.domain.port.CacheRepositoryPort;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Cache in maps.
 */
public final class InMemoryCache implements CacheRepositoryPort {

    public final Map<ComponentId, GlobalComponentEntry> components = new LinkedHashMap<>();
    public final Map<ScreenId, ScreenCacheEntry> screens = new LinkedHashMap<>();
    public final Map<MethodRef, StepCacheEntry> steps = new LinkedHashMap<>();
    public final Map<TestMethodRef, TestCacheEntry> tests = new LinkedHashMap<>();
    public final List<IosLearnedRule> rules = new ArrayList<>();

    @Override
    public List<GlobalComponentEntry> findGlobalComponents() {
        return List.copyOf(components.values());
    }

    @Override
    public Optional<GlobalComponentEntry> findGlobalComponent(ComponentId component) {
        return Optional.ofNullable(components.get(component));
    }

    @Override
    public void saveGlobalComponent(GlobalComponentEntry entry) {
        components.put(entry.component(), entry);
    }

    @Override
    public Optional<ScreenCacheEntry> findScreen(ScreenId screen) {
        return Optional.ofNullable(screens.get(screen));
    }

    @Override
    public List<ScreenCacheEntry> findAllScreens() {
        return List.copyOf(screens.values());
    }

    @Override
    public void saveScreen(ScreenCacheEntry entry) {
        screens.put(entry.screen(), entry);
    }

    @Override
    public Optional<StepCacheEntry> findStep(MethodRef step) {
        return Optional.ofNullable(steps.get(step));
    }

    @Override
    public void saveStep(StepCacheEntry entry) {
        steps.put(entry.step(), entry);
    }

    @Override
    public Optional<TestCacheEntry> findTest(TestMethodRef test) {
        return Optional.ofNullable(tests.get(test));
    }

    @Override
    public List<TestCacheEntry> findAllTests() {
        return List.copyOf(tests.values());
    }

    @Override
    public void saveTest(TestCacheEntry entry) {
        tests.put(entry.test(), entry);
    }

    @Override
    public List<IosLearnedRule> findLearnedRules() {
        return List.copyOf(rules);
    }

    @Override
    public void saveLearnedRule(IosLearnedRule rule) {
        rules.add(rule);
    }
}
