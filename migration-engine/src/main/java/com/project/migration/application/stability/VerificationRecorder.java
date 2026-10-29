package com.project.migration.application.stability;

import com.project.migration.domain.model.analysis.TestMethodTask;
import com.project.migration.domain.model.cache.GlobalComponentEntry;
import com.project.migration.domain.model.cache.ScreenCacheEntry;
import com.project.migration.domain.model.cache.ScreenElementMapping;
import com.project.migration.domain.model.cache.StepCacheEntry;
import com.project.migration.domain.model.cache.TestCacheEntry;
import com.project.migration.domain.model.cache.VerificationStatus;
import com.project.migration.domain.model.code.FieldRef;
import com.project.migration.domain.model.code.MethodRef;
import com.project.migration.domain.model.config.EngineConfig;
import com.project.migration.domain.model.report.TestMigrationStatus;
import com.project.migration.domain.model.screen.ScreenId;
import com.project.migration.domain.model.screen.ScreenStateKey;
import com.project.migration.domain.port.CacheRepositoryPort;
import com.project.migration.domain.port.CodeAnalyzerPort;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Writes what a gate result means into the cache: the steps, locators, screen states and global components a
 * test used become {@code VERIFIED_STABLE} after {@code 3/3 PASS}; after a code-related failure they become
 * {@code FAILED}, but never overwrite something another test already verified.
 */
final class VerificationRecorder {

    private final CacheRepositoryPort cache;
    private final CodeAnalyzerPort analyzer;
    private final EngineConfig config;
    private final Clock clock;

    VerificationRecorder(CacheRepositoryPort cache, CodeAnalyzerPort analyzer, EngineConfig config, Clock clock) {
        this.cache = cache;
        this.analyzer = analyzer;
        this.config = config;
        this.clock = clock;
    }

    void testStatus(TestMethodTask task, TestMigrationStatus status) {
        cache.saveTest(new TestCacheEntry(task.test(), status, clock.instant()));
    }

    void verified(TestMethodTask task, Map<String, ScreenId> screenOfClass) {
        Instant now = clock.instant();
        for (MethodRef step : task.callTree()) {
            cache.saveStep(StepCacheEntry.verified(step, task.test(), now));
        }
        updateScreens(task, screenOfClass, VerificationStatus.VERIFIED_STABLE);
        Set<String> classes = classesOf(task);
        for (GlobalComponentEntry entry : cache.findGlobalComponents()) {
            String componentClass = config.globalComponents().get(entry.component());
            if (componentClass != null && classes.contains(componentClass)
                    && entry.status() != VerificationStatus.VERIFIED_STABLE) {
                cache.saveGlobalComponent(new GlobalComponentEntry(entry.component(),
                        entry.mappings().stream().map(m -> m.withStatus(VerificationStatus.VERIFIED_STABLE)).toList(),
                        VerificationStatus.VERIFIED_STABLE, now));
            }
        }
    }

    void failed(TestMethodTask task, Map<String, ScreenId> screenOfClass) {
        Instant now = clock.instant();
        for (MethodRef step : task.callTree()) {
            boolean alreadyVerified = cache.findStep(step)
                    .filter(entry -> entry.status() == VerificationStatus.VERIFIED_STABLE).isPresent();
            if (!alreadyVerified) {
                cache.saveStep(new StepCacheEntry(step, VerificationStatus.FAILED, java.util.Optional.empty(), now));
            }
        }
        updateScreens(task, screenOfClass, VerificationStatus.FAILED);
    }

    private void updateScreens(TestMethodTask task, Map<String, ScreenId> screenOfClass, VerificationStatus target) {
        Instant now = clock.instant();
        Map<ScreenId, Set<FieldRef>> fieldsByScreen = new LinkedHashMap<>();
        for (MethodRef step : task.callTree()) {
            analyzer.findLocatorFields(step).forEach(field -> {
                ScreenId screen = screenOfClass.get(field.field().classFqcn());
                if (screen != null) {
                    fieldsByScreen.computeIfAbsent(screen, s -> new LinkedHashSet<>()).add(field.field());
                }
            });
        }
        Map<ScreenId, Set<ScreenStateKey>> statesByScreen = new LinkedHashMap<>();
        task.requiredStates().forEach(key -> statesByScreen.computeIfAbsent(key.screen(), s -> new LinkedHashSet<>()).add(key));
        Set<ScreenId> screens = new LinkedHashSet<>(fieldsByScreen.keySet());
        screens.addAll(statesByScreen.keySet());
        for (ScreenId screen : screens) {
            ScreenCacheEntry entry = cache.findScreen(screen).orElseGet(() -> ScreenCacheEntry.empty(screen, now));
            for (FieldRef field : fieldsByScreen.getOrDefault(screen, Set.of())) {
                for (ScreenElementMapping mapping : List.copyOf(entry.mappings())) {
                    if (mapping.field().equals(field) && mapping.status() != VerificationStatus.VERIFIED_STABLE) {
                        entry = entry.withMapping(mapping.withStatus(target), now);
                    }
                }
            }
            for (ScreenStateKey key : statesByScreen.getOrDefault(screen, Set.of())) {
                VerificationStatus current = entry.states().get(key);
                if (current != VerificationStatus.VERIFIED_STABLE && (current != null || target == VerificationStatus.VERIFIED_STABLE)) {
                    entry = entry.withState(key, target, now);
                }
            }
            cache.saveScreen(entry);
        }
    }

    private Set<String> classesOf(TestMethodTask task) {
        Set<String> classes = new LinkedHashSet<>();
        task.callTree().forEach(step -> {
            classes.add(step.classFqcn());
            analyzer.findLocatorFields(step).forEach(f -> classes.add(f.field().classFqcn()));
        });
        return classes;
    }
}
