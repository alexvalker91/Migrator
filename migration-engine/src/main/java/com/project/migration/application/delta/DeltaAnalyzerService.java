package com.project.migration.application.delta;

import com.project.migration.application.prompt.PromptTemplates;
import com.project.migration.application.telemetry.TelemetryCollector;
import com.project.migration.domain.model.Checks;
import com.project.migration.domain.model.MigrationStage;
import com.project.migration.domain.model.Platform;
import com.project.migration.domain.model.analysis.ComplexityMarker;
import com.project.migration.domain.model.analysis.ComplexityMarkerType;
import com.project.migration.domain.model.analysis.MigrationTaskDelta;
import com.project.migration.domain.model.analysis.PlatformScope;
import com.project.migration.domain.model.analysis.TestMethodTask;
import com.project.migration.domain.model.cache.ScreenCacheEntry;
import com.project.migration.domain.model.cache.StepCacheEntry;
import com.project.migration.domain.model.cache.VerificationStatus;
import com.project.migration.domain.model.code.AddTestGroups;
import com.project.migration.domain.model.code.FieldRef;
import com.project.migration.domain.model.code.LocatorField;
import com.project.migration.domain.model.code.MethodRef;
import com.project.migration.domain.model.code.TestMethodRef;
import com.project.migration.domain.model.llm.LlmRequest;
import com.project.migration.domain.model.llm.LlmTier;
import com.project.migration.domain.model.llm.SystemMessage;
import com.project.migration.domain.model.llm.UserMessage;
import com.project.migration.domain.model.screen.ScreenId;
import com.project.migration.domain.model.screen.ScreenState;
import com.project.migration.domain.model.screen.ScreenStateKey;
import com.project.migration.domain.port.CacheRepositoryPort;
import com.project.migration.domain.port.CodeAnalyzerPort;
import com.project.migration.domain.port.LlmClientPort;
import com.project.migration.domain.port.MigrationLoggerPort;
import com.project.migration.domain.port.SnapshotRepositoryPort;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Stage 1: what is left to do for a test class. The AST work (tests, call trees, locators, markers) and the
 * subtraction of everything {@code VERIFIED_STABLE} in the cache are deterministic. One Tier-3 call decides
 * what the code alone cannot say: which Page Object is which screen and which screen states each test reaches.
 * The model's answer is validated, repaired once, and completed with the invariant that every screen a test
 * touches needs its {@code DEFAULT} state.
 */
public final class DeltaAnalyzerService {

    private static final int METHOD_SOURCE_LIMIT = 2_500;

    private final CodeAnalyzerPort analyzer;
    private final CacheRepositoryPort cache;
    private final SnapshotRepositoryPort snapshots;
    private final LlmClientPort llm;
    private final TelemetryCollector telemetry;
    private final MigrationLoggerPort logger;

    public DeltaAnalyzerService(CodeAnalyzerPort analyzer, CacheRepositoryPort cache, SnapshotRepositoryPort snapshots,
                                LlmClientPort llm, TelemetryCollector telemetry, MigrationLoggerPort logger) {
        this.analyzer = Checks.notNull(analyzer, "analyzer");
        this.cache = Checks.notNull(cache, "cache");
        this.snapshots = Checks.notNull(snapshots, "snapshots");
        this.llm = Checks.notNull(llm, "llm");
        this.telemetry = Checks.notNull(telemetry, "telemetry");
        this.logger = Checks.notNull(logger, "logger");
    }

    /**
     * @throws com.project.migration.domain.exception.LlmCallException if the gateway fails
     * @throws IllegalStateException if two Page Objects with the same simple name would share one screen id
     */
    public MigrationTaskDelta analyze(String testClassFqcn) {
        List<TestMethodRef> tests = analyzer.listTestMethods(testClassFqcn);
        if (tests.isEmpty()) {
            logger.warn("No @Test methods found in " + testClassFqcn);
            return MigrationTaskDelta.empty(testClassFqcn);
        }

        List<Candidate> candidates = new ArrayList<>();
        for (TestMethodRef test : tests) {
            if (cache.findTest(test).filter(entry -> entry.status().isMigrated()).isPresent()) {
                telemetry.cacheHit();
                logger.info("Test " + test.methodName() + " is already migrated, skipped");
                continue;
            }
            candidates.add(candidate(test));
        }
        List<Candidate> iosWork = candidates.stream().filter(c -> c.scope().includesIos()).toList();

        Map<MethodRef, Boolean> verifiedByStep = new LinkedHashMap<>();
        Map<FieldRef, LocatorField> fields = new LinkedHashMap<>();
        for (Candidate candidate : iosWork) {
            for (MethodRef step : candidate.callTree()) {
                verifiedByStep.computeIfAbsent(step, this::isVerified);
                analyzer.findLocatorFields(step).forEach(field -> fields.putIfAbsent(field.field(), field));
            }
        }

        Map<String, ScreenId> screenOfClass = knownScreens(fields.keySet());
        Set<FieldRef> unmapped = fields.values().stream()
                .filter(field -> !isMapped(field, screenOfClass.get(field.field().classFqcn())))
                .map(LocatorField::field).collect(Collectors.toCollection(LinkedHashSet::new));
        fields.keySet().stream().filter(field -> !unmapped.contains(field)).forEach(field -> telemetry.cacheHit());

        Map<String, Set<ScreenStateKey>> modelStates = Map.of();
        if (!unmapped.isEmpty()) {
            ValidatedAnalysis analysis = askModel(testClassFqcn, iosWork, fields.keySet(), screenOfClass, unmapped);
            analysis.assignments().forEach((fqcn, screen) -> screenOfClass.putIfAbsent(fqcn, screen));
            modelStates = analysis.states();
        }
        completeWithDefaultScreens(fields.keySet(), screenOfClass);

        Set<ScreenId> workScreens = unmapped.stream().map(field -> screenOfClass.get(field.classFqcn()))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        List<TestMethodTask> tasks = new ArrayList<>();
        Set<ScreenStateKey> missing = new LinkedHashSet<>();
        for (Candidate candidate : candidates) {
            Set<ScreenStateKey> required = candidate.scope().includesIos()
                    ? requiredStates(candidate, screenOfClass, modelStates) : Set.of();
            for (ScreenStateKey key : required) {
                if (!workScreens.contains(key.screen())) {
                    continue;
                }
                if (snapshots.exists(key, Platform.ANDROID) && snapshots.exists(key, Platform.IOS)) {
                    telemetry.cacheHit();
                } else {
                    telemetry.cacheMiss();
                    missing.add(key);
                }
            }
            tasks.add(new TestMethodTask(candidate.test(), candidate.callTree(), required, candidate.markers(),
                    candidate.scope()));
        }

        Set<MethodRef> toAdapt = new LinkedHashSet<>();
        Set<MethodRef> verified = new LinkedHashSet<>();
        verifiedByStep.forEach((step, isVerified) -> {
            if (isVerified) {
                telemetry.cacheHit();
                verified.add(step);
            } else {
                telemetry.cacheMiss();
                toAdapt.add(step);
            }
        });
        unmapped.forEach(field -> telemetry.cacheMiss());
        Set<ScreenId> verifiedScreens = new LinkedHashSet<>();
        screenOfClass.values().stream().distinct()
                .filter(screen -> cache.findScreen(screen).map(ScreenCacheEntry::status)
                        .filter(VerificationStatus.VERIFIED_STABLE::equals).isPresent())
                .forEach(verifiedScreens::add);

        MigrationTaskDelta delta = new MigrationTaskDelta(testClassFqcn, tasks, missing, unmapped, toAdapt, verified,
                verifiedScreens, screenOfClass);
        logger.info("Delta of " + testClassFqcn + ": " + tasks.size() + " tests, " + unmapped.size()
                + " locators to map, " + toAdapt.size() + " steps to adapt (" + verified.size() + " verified), "
                + missing.size() + " snapshot states missing");
        return delta;
    }

    private Candidate candidate(TestMethodRef test) {
        List<MethodRef> callTree = analyzer.resolveCallTree(test);
        Set<ComplexityMarker> markers = new LinkedHashSet<>(analyzer.detectComplexityMarkers(test));
        callTree.forEach(step -> markers.addAll(analyzer.detectComplexityMarkers(step)));
        return new Candidate(test, callTree, markers, scopeOf(test, markers));
    }

    private PlatformScope scopeOf(TestMethodRef test, Set<ComplexityMarker> markers) {
        Set<String> groups = analyzer.findTestGroups(test);
        if (groups.contains(AddTestGroups.ANDROID_ONLY)) {
            return PlatformScope.ANDROID_ONLY;
        }
        if (groups.contains(AddTestGroups.IOS_ONLY)) {
            return PlatformScope.IOS_ONLY;
        }
        boolean platformFeature = markers.stream().anyMatch(m -> m.type() == ComplexityMarkerType.PLATFORM_SPECIFIC_FEATURE);
        return platformFeature ? PlatformScope.ANDROID_ONLY : PlatformScope.BOTH;
    }

    private boolean isVerified(MethodRef step) {
        return cache.findStep(step).map(StepCacheEntry::status).filter(VerificationStatus.VERIFIED_STABLE::equals).isPresent();
    }

    private boolean isMapped(LocatorField field, ScreenId screen) {
        if (field.hasIosLocator()) {
            return true;
        }
        return screen != null && cache.findScreen(screen)
                .flatMap(entry -> entry.mappings().stream().filter(m -> m.field().equals(field.field())).findFirst())
                .filter(mapping -> mapping.isVerified()).isPresent();
    }

    /** Screens the cache already ties to these Page Object classes (a mapping of one of their fields, or the screen named like the class). */
    private Map<String, ScreenId> knownScreens(Set<FieldRef> fields) {
        Map<String, ScreenId> known = new LinkedHashMap<>();
        List<ScreenCacheEntry> entries = cache.findAllScreens();
        for (String classFqcn : fields.stream().map(FieldRef::classFqcn).collect(Collectors.toCollection(LinkedHashSet::new))) {
            entries.stream().filter(entry -> entry.mappings().stream()
                            .anyMatch(mapping -> mapping.field().classFqcn().equals(classFqcn)))
                    .map(ScreenCacheEntry::screen).findFirst()
                    .or(() -> entries.stream().map(ScreenCacheEntry::screen)
                            .filter(screen -> screen.value().equals(DeltaAnalysisValidator.simpleName(classFqcn))).findFirst())
                    .ifPresent(screen -> known.put(classFqcn, screen));
        }
        return known;
    }

    private void completeWithDefaultScreens(Set<FieldRef> fields, Map<String, ScreenId> screenOfClass) {
        Map<ScreenId, String> owners = new HashMap<>();
        screenOfClass.forEach((fqcn, screen) -> owners.putIfAbsent(screen, fqcn));
        for (String classFqcn : fields.stream().map(FieldRef::classFqcn).distinct().toList()) {
            if (screenOfClass.containsKey(classFqcn)) {
                continue;
            }
            ScreenId screen = new ScreenId(DeltaAnalysisValidator.simpleName(classFqcn));
            String owner = owners.putIfAbsent(screen, classFqcn);
            if (owner != null && !owner.equals(classFqcn)) {
                throw new IllegalStateException("Page Objects " + owner + " and " + classFqcn
                        + " would share the screen id '" + screen + "'; give them different class names or"
                        + " map them to distinct screens in the cache");
            }
            screenOfClass.put(classFqcn, screen);
        }
    }

    private Set<ScreenStateKey> requiredStates(Candidate candidate, Map<String, ScreenId> screenOfClass,
                                               Map<String, Set<ScreenStateKey>> modelStates) {
        Set<ScreenStateKey> required = new LinkedHashSet<>();
        Set<String> classes = new LinkedHashSet<>();
        for (MethodRef step : candidate.callTree()) {
            analyzer.findLocatorFields(step).forEach(field -> classes.add(field.field().classFqcn()));
        }
        classes.stream().map(screenOfClass::get).filter(Objects::nonNull)
                .forEach(screen -> required.add(ScreenStateKey.of(screen, ScreenState.DEFAULT)));
        required.addAll(modelStates.getOrDefault(candidate.test().methodName(), Set.of()));
        return required;
    }

    private ValidatedAnalysis askModel(String testClassFqcn, List<Candidate> iosWork, Set<FieldRef> allFields,
                                       Map<String, ScreenId> screenOfClass, Set<FieldRef> unmapped) {
        Set<String> classes = allFields.stream().map(FieldRef::classFqcn).collect(Collectors.toCollection(LinkedHashSet::new));
        List<Candidate> asked = iosWork.stream().filter(candidate -> candidate.callTree().stream()
                .flatMap(step -> analyzer.findLocatorFields(step).stream())
                .anyMatch(field -> unmapped.contains(field.field()))).toList();
        DeltaAnalysisValidator validator = new DeltaAnalysisValidator(classes,
                asked.stream().map(c -> c.test().methodName()).collect(Collectors.toSet()),
                cache.findAllScreens().stream().map(ScreenCacheEntry::screen).collect(Collectors.toSet()));

        String purpose = "delta-analysis:" + DeltaAnalysisValidator.simpleName(testClassFqcn);
        ValidatedAnalysis first = validator.validate(ask(purpose, asked, classes, screenOfClass, ""));
        if (first.problems().isEmpty()) {
            return first;
        }
        logger.warn("Delta analysis answer had problems, asking once more: " + first.problems());
        String problems = "\nYour previous answer had these problems, answer again completely and fix them:\n- "
                + String.join("\n- ", first.problems());
        ValidatedAnalysis second = validator.validate(ask(purpose + ":repair", asked, classes, screenOfClass, problems));
        second.problems().forEach(problem -> logger.warn("Delta analysis: dropped after repair: " + problem));
        return second;
    }

    private DeltaAnalysis ask(String purpose, List<Candidate> asked, Set<String> classes,
                              Map<String, ScreenId> screenOfClass, String problems) {
        String system = PromptTemplates.render("delta-analysis.system", Map.of("states", Arrays.stream(ScreenState.values())
                .map(Enum::name).collect(Collectors.joining(", "))));
        String known = cache.findAllScreens().stream().map(entry -> entry.screen().value())
                .collect(Collectors.joining(", "));
        String pageObjects = classes.stream().map(fqcn -> "- " + fqcn + screenOfClass.entrySet().stream()
                        .filter(entry -> entry.getKey().equals(fqcn)).findFirst()
                        .map(entry -> " -> " + entry.getValue()).orElse(""))
                .collect(Collectors.joining("\n"));
        String user = PromptTemplates.render("delta-analysis.user", Map.of(
                "knownScreens", known.isEmpty() ? "(none yet)" : known,
                "pageObjects", pageObjects,
                "tests", asked.stream().map(this::describe).collect(Collectors.joining("\n\n")),
                "problems", problems));
        return llm.chat(LlmRequest.structured(LlmTier.TIER_3, MigrationStage.STAGE_1_DELTA_ANALYSIS, purpose,
                List.of(new SystemMessage(system), UserMessage.of(user)), DeltaAnalysis.class)).requireContent();
    }

    private String describe(Candidate candidate) {
        StringBuilder text = new StringBuilder("### Test `").append(candidate.test().methodName()).append("`\n```java\n")
                .append(analyzer.testSource(candidate.test()).orElse("// source unavailable")).append("\n```\n");
        for (MethodRef step : candidate.callTree()) {
            String source = analyzer.methodSource(step).orElse("// source unavailable");
            if (source.length() > METHOD_SOURCE_LIMIT) {
                source = source.substring(0, METHOD_SOURCE_LIMIT) + "\n// ... cut";
            }
            text.append("Step `").append(step.key()).append("`:\n```java\n").append(source).append("\n```\n");
        }
        return text.toString();
    }

    private record Candidate(TestMethodRef test, List<MethodRef> callTree, Set<ComplexityMarker> markers,
                             PlatformScope scope) {
    }
}
