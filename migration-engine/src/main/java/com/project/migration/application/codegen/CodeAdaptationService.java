package com.project.migration.application.codegen;

import com.project.migration.domain.model.Checks;
import com.project.migration.domain.model.analysis.MigrationTaskDelta;
import com.project.migration.domain.model.analysis.PlatformScope;
import com.project.migration.domain.model.analysis.TestMethodTask;
import com.project.migration.domain.model.cache.ScreenCacheEntry;
import com.project.migration.domain.model.cache.ScreenElementMapping;
import com.project.migration.domain.model.cache.VerificationStatus;
import com.project.migration.domain.model.code.AddIosLocator;
import com.project.migration.domain.model.code.AddTestGroups;
import com.project.migration.domain.model.code.CodeChange;
import com.project.migration.domain.model.code.FieldRef;
import com.project.migration.domain.model.code.FrameworkContract;
import com.project.migration.domain.model.code.InsertCallBefore;
import com.project.migration.domain.model.code.LocatorField;
import com.project.migration.domain.model.code.MethodRef;
import com.project.migration.domain.model.code.ReplaceMethodBody;
import com.project.migration.domain.model.code.TestMethodRef;
import com.project.migration.domain.model.codegen.CodeAdaptationResult;
import com.project.migration.domain.model.codegen.ManualAdaptation;
import com.project.migration.domain.model.codegen.MappedLocator;
import com.project.migration.domain.model.codegen.UnresolvedLocator;
import com.project.migration.domain.model.screen.ScreenId;
import com.project.migration.domain.port.CacheRepositoryPort;
import com.project.migration.domain.port.CodeAnalyzerPort;
import com.project.migration.domain.port.CodeModifierPort;
import com.project.migration.domain.port.LlmClientPort;
import com.project.migration.domain.port.MigrationLoggerPort;
import com.project.migration.domain.port.SnapshotRepositoryPort;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stage 3 (without compilation and commit, which the orchestrator owns): turns the delta and the stored
 * snapshots into source changes. Order of work: iOS locators for annotated fields (Tier-2, validated against
 * the iOS snapshot), Tier-1 rewrites of methods with Android-specific logic, scroll calls before off-screen
 * elements, platform groups for Android-only tests. All changes are applied through {@link CodeModifierPort}.
 * What could not be done is returned, never thrown; what was done is recorded in the cache as {@code UNVERIFIED}
 * until the 3/3 gate confirms it.
 */
public final class CodeAdaptationService implements CodeAdapter {

    private final CodeAnalyzerPort analyzer;
    private final ResilientApplier applier;
    private final CacheRepositoryPort cache;
    private final MigrationLoggerPort logger;
    private final Clock clock;
    private final LocatorMapper locatorMapper;
    private final MethodAdapter methodAdapter;

    public CodeAdaptationService(CodeAnalyzerPort analyzer, CodeModifierPort modifier, CacheRepositoryPort cache,
                                 SnapshotRepositoryPort snapshots, LlmClientPort llm, MigrationLoggerPort logger,
                                 Clock clock) {
        this.analyzer = Checks.notNull(analyzer, "analyzer");
        this.applier = new ResilientApplier(Checks.notNull(modifier, "modifier"), logger);
        this.cache = Checks.notNull(cache, "cache");
        this.logger = Checks.notNull(logger, "logger");
        this.clock = Checks.notNull(clock, "clock");
        this.locatorMapper = new LocatorMapper(snapshots, llm, logger);
        this.methodAdapter = new MethodAdapter(analyzer, snapshots, llm, logger);
    }

    /**
     * @throws com.project.migration.domain.exception.LlmCallException if the gateway fails
     */
    @Override
    public CodeAdaptationResult adapt(MigrationTaskDelta delta) {
        List<TestMethodTask> iosTasks = delta.tasks().stream().filter(task -> task.scope().includesIos()).toList();
        Set<MethodRef> iosSteps = new LinkedHashSet<>();
        Map<FieldRef, LocatorField> fields = new LinkedHashMap<>();
        for (TestMethodTask task : iosTasks) {
            for (MethodRef step : task.callTree()) {
                iosSteps.add(step);
                analyzer.findLocatorFields(step).stream().filter(f -> delta.locatorsToMap().contains(f.field()))
                        .forEach(f -> fields.putIfAbsent(f.field(), f));
            }
        }

        MappingOutcome mapping = locatorMapper.map(delta, fields);
        List<MethodRef> rewriteCandidates = iosSteps.stream().filter(delta.stepsToAdapt()::contains)
                .filter(methodAdapter::needsAdaptation).toList();
        AdaptationOutcome adaptation = methodAdapter.adapt(rewriteCandidates, delta.screenOfClass());
        warnAboutTestBodies(iosTasks);

        List<CodeChange> changes = new ArrayList<>(mapping.changes());
        changes.addAll(adaptation.changes());
        changes.addAll(scrollCalls(mapping.scrollTexts(), iosSteps, Set.copyOf(rewriteCandidates)));
        List<AddTestGroups> tags = androidOnlyTags(delta.tasks());
        changes.addAll(tags);

        ResilientApplier.Applied applied = applier.apply(changes);

        List<MappedLocator> mapped = new ArrayList<>();
        List<UnresolvedLocator> unresolved = new ArrayList<>(mapping.unresolved());
        for (MappedLocator locator : mapping.mapped()) {
            String failure = applied.failures().get(new AddIosLocator(locator.field(), locator.strategy(), locator.value()));
            if (failure == null) {
                mapped.add(locator);
            } else {
                unresolved.add(new UnresolvedLocator(locator.field(), "the annotation could not be applied: " + failure));
            }
        }
        List<MethodRef> adapted = new ArrayList<>();
        List<ManualAdaptation> manual = new ArrayList<>(adaptation.manual());
        for (MethodRef method : adaptation.adapted()) {
            String failure = applied.failures().entrySet().stream()
                    .filter(e -> e.getKey() instanceof ReplaceMethodBody r && r.method().equals(method))
                    .map(Map.Entry::getValue).findFirst().orElse(null);
            if (failure == null) {
                adapted.add(method);
            } else {
                manual.add(new ManualAdaptation(method, "the rewrite could not be applied: " + failure));
            }
        }
        List<TestMethodRef> tagged = tags.stream().filter(tag -> !applied.failures().containsKey(tag))
                .map(AddTestGroups::test).toList();

        recordInCache(mapped, fields);
        logger.info("Code adaptation: " + mapped.size() + " locators mapped, " + unresolved.size() + " unresolved, "
                + mapping.conflicts().size() + " conflicts, " + adapted.size() + " methods rewritten, " + manual.size()
                + " need manual work, " + tagged.size() + " tests tagged");
        return new CodeAdaptationResult(applied.result(), mapped, unresolved, mapping.conflicts(), adapted, manual, tagged);
    }

    private void warnAboutTestBodies(List<TestMethodTask> iosTasks) {
        for (TestMethodTask task : iosTasks) {
            if (!analyzer.detectComplexityMarkers(task.test()).isEmpty()) {
                logger.warn("Test " + task.test().key() + " itself contains Android-specific constructs; the test body "
                        + "is not adapted automatically");
            }
        }
    }

    /** {@code scrollToElementByText} before the first statement that uses a field whose iOS element is off screen. */
    private List<InsertCallBefore> scrollCalls(Map<FieldRef, String> scrollTexts, Set<MethodRef> iosSteps,
                                               Set<MethodRef> rewritten) {
        List<InsertCallBefore> inserts = new ArrayList<>();
        scrollTexts.forEach((field, text) -> {
            String call = FrameworkContract.SCROLL_TO_ELEMENT_BY_TEXT + "(\"" + escape(text) + "\");";
            for (MethodRef method : iosSteps) {
                if (rewritten.contains(method)
                        || analyzer.findLocatorFields(method).stream().noneMatch(f -> f.field().equals(field))) {
                    continue;
                }
                List<String> anchors = analyzer.statementsUsingField(method, field);
                boolean present = analyzer.methodSource(method).map(source -> source.contains(
                        FrameworkContract.SCROLL_TO_ELEMENT_BY_TEXT + "(\"" + escape(text) + "\")")).orElse(true);
                if (!anchors.isEmpty() && !present) {
                    inserts.add(new InsertCallBefore(method, anchors.getFirst(), call));
                }
            }
        });
        return inserts;
    }

    private List<AddTestGroups> androidOnlyTags(List<TestMethodTask> tasks) {
        return tasks.stream().filter(task -> task.scope() == PlatformScope.ANDROID_ONLY)
                .filter(task -> !analyzer.findTestGroups(task.test()).contains(AddTestGroups.ANDROID_ONLY))
                .map(task -> new AddTestGroups(task.test(), Set.of(AddTestGroups.ANDROID_ONLY))).toList();
    }

    private void recordInCache(List<MappedLocator> mapped, Map<FieldRef, LocatorField> fields) {
        Map<ScreenId, ScreenCacheEntry> updated = new LinkedHashMap<>();
        for (MappedLocator locator : mapped) {
            ScreenId screen = locator.foundIn().screen();
            ScreenCacheEntry entry = updated.computeIfAbsent(screen,
                    id -> cache.findScreen(id).orElseGet(() -> ScreenCacheEntry.empty(id, clock.instant())));
            LocatorField field = fields.get(locator.field());
            entry = entry.withMapping(new ScreenElementMapping(locator.field(), field.androidLocator(),
                    locator.strategy(), locator.value(), VerificationStatus.UNVERIFIED), clock.instant());
            if (!entry.states().containsKey(locator.foundIn())) {
                entry = entry.withState(locator.foundIn(), VerificationStatus.UNVERIFIED, clock.instant());
            }
            updated.put(screen, entry);
        }
        updated.values().forEach(cache::saveScreen);
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
