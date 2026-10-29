package com.project.migration.infrastructure.cache;

import com.project.migration.domain.model.cache.GlobalComponentEntry;
import com.project.migration.domain.model.cache.IosLearnedRule;
import com.project.migration.domain.model.cache.ScreenCacheEntry;
import com.project.migration.domain.model.cache.ScreenElementMapping;
import com.project.migration.domain.model.cache.StepCacheEntry;
import com.project.migration.domain.model.cache.TestCacheEntry;
import com.project.migration.domain.model.cache.VerificationStatus;
import com.project.migration.domain.model.code.FieldRef;
import com.project.migration.domain.model.code.MethodRef;
import com.project.migration.domain.model.code.TestMethodRef;
import com.project.migration.domain.model.screen.ComponentId;
import com.project.migration.domain.model.screen.ScreenId;
import com.project.migration.domain.model.screen.ScreenStateKey;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Domain ↔ {@link CacheJson} conversion. Lists are written in a stable order so shard diffs stay readable.
 */
final class CacheJsonMapper {

    private CacheJsonMapper() {
    }

    static CacheJson.GlobalComponent toJson(GlobalComponentEntry entry) {
        return new CacheJson.GlobalComponent(entry.component().value(), mappingsToJson(entry.mappings()),
                entry.status(), entry.updatedAt());
    }

    static GlobalComponentEntry toDomain(CacheJson.GlobalComponent json) {
        return new GlobalComponentEntry(new ComponentId(json.component()), mappingsToDomain(json.mappings()),
                json.status(), json.updatedAt());
    }

    static CacheJson.LearnedRule toJson(IosLearnedRule rule) {
        return new CacheJson.LearnedRule(rule.id(), rule.description(),
                rule.learnedFrom().map(CacheJsonMapper::toJson).orElse(null), rule.learnedAt());
    }

    static IosLearnedRule toDomain(CacheJson.LearnedRule json) {
        return new IosLearnedRule(json.id(), json.description(),
                Optional.ofNullable(json.learnedFrom()).map(CacheJsonMapper::toDomain), json.learnedAt());
    }

    static CacheJson.ScreenShard toJson(ScreenCacheEntry entry) {
        List<CacheJson.State> states = entry.states().entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing((ScreenStateKey key) -> key.state())
                        .thenComparing(ScreenStateKey::variant)))
                .map(state -> new CacheJson.State(state.getKey().state(), state.getKey().variant(), state.getValue()))
                .toList();
        return new CacheJson.ScreenShard(CacheJson.SCHEMA_VERSION, entry.screen().value(), states,
                mappingsToJson(entry.mappings()), entry.updatedAt());
    }

    static ScreenCacheEntry toDomain(CacheJson.ScreenShard json) {
        ScreenId screen = new ScreenId(json.screen());
        Map<ScreenStateKey, VerificationStatus> states = new LinkedHashMap<>();
        for (CacheJson.State state : json.states()) {
            states.put(ScreenStateKey.of(screen, state.state(), state.variant()), state.status());
        }
        return new ScreenCacheEntry(screen, states, mappingsToDomain(json.mappings()), json.updatedAt());
    }

    static CacheJson.Step toJson(StepCacheEntry entry) {
        return new CacheJson.Step(entry.step().methodName(), entry.step().parameterTypes(), entry.status(),
                entry.verifiedBy().map(CacheJsonMapper::toJson).orElse(null), entry.updatedAt());
    }

    static StepCacheEntry toDomain(String stepClass, CacheJson.Step json) {
        return new StepCacheEntry(new MethodRef(stepClass, json.method(), json.parameterTypes()), json.status(),
                Optional.ofNullable(json.verifiedBy()).map(CacheJsonMapper::toDomain), json.updatedAt());
    }

    static CacheJson.Test toJson(TestCacheEntry entry) {
        return new CacheJson.Test(entry.test().methodName(), entry.status(), entry.updatedAt());
    }

    static TestCacheEntry toDomain(String testClass, CacheJson.Test json) {
        return new TestCacheEntry(new TestMethodRef(testClass, json.method()), json.status(), json.updatedAt());
    }

    private static CacheJson.TestRef toJson(TestMethodRef test) {
        return new CacheJson.TestRef(test.classFqcn(), test.methodName());
    }

    private static TestMethodRef toDomain(CacheJson.TestRef json) {
        return new TestMethodRef(json.testClass(), json.method());
    }

    private static List<CacheJson.Mapping> mappingsToJson(List<ScreenElementMapping> mappings) {
        return mappings.stream()
                .sorted(Comparator.comparing(mapping -> mapping.field().key()))
                .map(mapping -> new CacheJson.Mapping(mapping.field().classFqcn(), mapping.field().fieldName(),
                        mapping.androidLocator(), mapping.iosStrategy(), mapping.iosLocator(), mapping.status()))
                .toList();
    }

    private static List<ScreenElementMapping> mappingsToDomain(List<CacheJson.Mapping> mappings) {
        return mappings.stream()
                .map(json -> new ScreenElementMapping(new FieldRef(json.fieldClass(), json.fieldName()),
                        json.androidLocator(), json.iosStrategy(), json.iosLocator(), json.status()))
                .toList();
    }
}
