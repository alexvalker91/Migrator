package com.project.migration.application.codegen;

import com.project.migration.application.prompt.PromptTemplates;
import com.project.migration.domain.model.MigrationStage;
import com.project.migration.domain.model.Platform;
import com.project.migration.domain.model.analysis.MigrationTaskDelta;
import com.project.migration.domain.model.code.AddIosLocator;
import com.project.migration.domain.model.code.FieldRef;
import com.project.migration.domain.model.code.IosLocatorStrategy;
import com.project.migration.domain.model.code.LocatorField;
import com.project.migration.domain.model.codegen.LocatorConflict;
import com.project.migration.domain.model.codegen.MappedLocator;
import com.project.migration.domain.model.codegen.UnresolvedLocator;
import com.project.migration.domain.model.llm.LlmRequest;
import com.project.migration.domain.model.llm.LlmTier;
import com.project.migration.domain.model.llm.SystemMessage;
import com.project.migration.domain.model.llm.UserMessage;
import com.project.migration.domain.model.screen.ScreenId;
import com.project.migration.domain.model.screen.ScreenStateKey;
import com.project.migration.domain.model.screen.StateSnapshot;
import com.project.migration.domain.port.LlmClientPort;
import com.project.migration.domain.port.MigrationLoggerPort;
import com.project.migration.domain.port.SnapshotRepositoryPort;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Stage 3.1: iOS locators for the fields of a screen, from the stored snapshot pairs. For every state of the
 * screen (DEFAULT first) one Tier-2 call proposes locators for the fields still open; the engine validates each
 * proposal against the iOS snapshot. Rejected proposals get one repair round on Tier-2, then one on Tier-1.
 * A field gets exactly one locator; it is cross-checked against the other states where the Android locator
 * finds the element, and a disagreement is reported as a conflict.
 */
final class LocatorMapper {

    private final SnapshotRepositoryPort snapshots;
    private final LlmClientPort llm;
    private final MigrationLoggerPort logger;

    LocatorMapper(SnapshotRepositoryPort snapshots, LlmClientPort llm, MigrationLoggerPort logger) {
        this.snapshots = snapshots;
        this.llm = llm;
        this.logger = logger;
    }

    MappingOutcome map(MigrationTaskDelta delta, Map<FieldRef, LocatorField> fields) {
        Map<ScreenId, List<LocatorField>> byScreen = new LinkedHashMap<>();
        for (FieldRef ref : delta.locatorsToMap()) {
            LocatorField field = fields.get(ref);
            ScreenId screen = delta.screenOfClass().get(ref.classFqcn());
            if (field != null && screen != null) {
                byScreen.computeIfAbsent(screen, s -> new ArrayList<>()).add(field);
            }
        }
        List<AddIosLocator> changes = new ArrayList<>();
        List<MappedLocator> mapped = new ArrayList<>();
        List<UnresolvedLocator> unresolved = new ArrayList<>();
        List<LocatorConflict> conflicts = new ArrayList<>();
        Map<FieldRef, String> scrollTexts = new LinkedHashMap<>();
        for (Map.Entry<ScreenId, List<LocatorField>> entry : byScreen.entrySet()) {
            mapScreen(entry.getKey(), entry.getValue(), changes, mapped, unresolved, conflicts, scrollTexts);
        }
        return new MappingOutcome(changes, mapped, unresolved, conflicts, scrollTexts);
    }

    private void mapScreen(ScreenId screen, List<LocatorField> screenFields, List<AddIosLocator> changes,
                           List<MappedLocator> mapped, List<UnresolvedLocator> unresolved,
                           List<LocatorConflict> conflicts, Map<FieldRef, String> scrollTexts) {
        List<Pair> states = statePairs(screen);
        if (states.isEmpty()) {
            screenFields.forEach(f -> unresolved.add(new UnresolvedLocator(f.field(),
                    "no Android/iOS snapshot pair stored for screen " + screen)));
            return;
        }
        Map<FieldRef, Resolution> resolved = new LinkedHashMap<>();
        Map<FieldRef, String> lastProblem = new LinkedHashMap<>();
        List<LocatorField> pending = new ArrayList<>(screenFields);
        for (Pair pair : states) {
            if (pending.isEmpty()) {
                break;
            }
            Map<FieldRef, Resolution> found = resolveInState(pair, pending, lastProblem);
            resolved.putAll(found);
            pending.removeIf(field -> found.containsKey(field.field()));
        }
        for (LocatorField field : pending) {
            unresolved.add(new UnresolvedLocator(field.field(), lastProblem.getOrDefault(field.field(),
                    "element not found in any captured state of " + screen)));
        }
        Map<FieldRef, LocatorField> byRef = screenFields.stream().collect(Collectors.toMap(LocatorField::field, f -> f));
        resolved.forEach((ref, resolution) -> {
            changes.add(new AddIosLocator(ref, resolution.strategy(), resolution.value()));
            mapped.add(new MappedLocator(ref, resolution.state(), resolution.strategy(), resolution.value()));
            resolution.scrollText().ifPresent(text -> scrollTexts.put(ref, text));
            conflictsOf(byRef.get(ref), resolution, states).forEach(conflicts::add);
        });
    }

    private Map<FieldRef, Resolution> resolveInState(Pair pair, List<LocatorField> pending, Map<FieldRef, String> lastProblem) {
        Map<FieldRef, Resolution> accepted = new LinkedHashMap<>();
        List<LocatorField> open = new ArrayList<>(pending);
        String problems = "";
        for (LlmTier tier : List.of(LlmTier.TIER_2, LlmTier.TIER_2, LlmTier.TIER_1)) {
            if (open.isEmpty()) {
                break;
            }
            Map<FieldRef, String> rejected = new LinkedHashMap<>();
            LocatorMappingAnswer answer = ask(tier, pair, open, problems);
            Map<String, LocatorMappingAnswer.FieldAnswer> byId = new LinkedHashMap<>();
            orEmpty(answer.fields()).forEach(a -> byId.putIfAbsent(String.valueOf(a.field()), a));
            for (LocatorField field : open) {
                LocatorMappingAnswer.FieldAnswer proposed = byId.get(field.field().key());
                if (proposed == null || !proposed.found()) {
                    continue;
                }
                Optional<IosLocatorStrategy> strategy = strategyOf(proposed.strategy());
                if (strategy.isEmpty() || proposed.value() == null || proposed.value().isBlank()) {
                    rejected.put(field.field(), "unknown strategy '" + proposed.strategy() + "' or empty value");
                    continue;
                }
                IosLocatorValidator.Verdict verdict = IosLocatorValidator.validate(pair.iosTree(), strategy.get(),
                        proposed.value(), field.collection());
                if (verdict.isValid()) {
                    accepted.put(field.field(), new Resolution(pair.key(), strategy.get(), proposed.value(),
                            verdict.first().filter(IosLocatorValidator.IosElement::offscreen)
                                    .map(IosLocatorValidator.IosElement::visibleText).filter(t -> !t.isBlank())));
                } else {
                    rejected.put(field.field(), strategy.get().annotationAttribute() + " \"" + proposed.value()
                            + "\" " + verdict.problem().orElse(""));
                }
            }
            lastProblem.putAll(rejected);
            open = open.stream().filter(f -> rejected.containsKey(f.field())).toList();
            problems = open.isEmpty() ? "" : "\nThese proposals were rejected by the engine's check against the iOS "
                    + "XML. Propose different locators for these fields:\n" + open.stream()
                    .map(f -> "- " + f.field().key() + ": " + rejected.get(f.field())).collect(Collectors.joining("\n"));
        }
        accepted.keySet().forEach(lastProblem::remove);
        return accepted;
    }

    private LocatorMappingAnswer ask(LlmTier tier, Pair pair, List<LocatorField> open, String problems) {
        String user = PromptTemplates.render("locator-mapping.user", Map.of(
                "screen", pair.key().screen().value(),
                "state", pair.key().state() + (pair.key().isDefaultVariant() ? "" : " (variant " + pair.key().variant() + ")"),
                "androidXml", pair.android().cleanXml(),
                "iosXml", pair.ios().cleanXml(),
                "fields", open.stream().map(f -> f.field().key() + " | " + f.androidLocator()
                        + " | " + (f.collection() ? "list of elements" : "single element")).collect(Collectors.joining("\n")),
                "problems", problems));
        return llm.chat(LlmRequest.structured(tier, MigrationStage.STAGE_3_CODEGEN,
                "locator-mapping:" + pair.key() + (problems.isEmpty() ? "" : ":repair"),
                List.of(new SystemMessage(PromptTemplates.render("locator-mapping.system",
                        Map.of("forms", IosLocatorValidator.ALLOWED_FORMS))), UserMessage.of(user)),
                LocatorMappingAnswer.class)).requireContent();
    }

    private List<LocatorConflict> conflictsOf(LocatorField field, Resolution chosen, List<Pair> states) {
        List<LocatorConflict> conflicts = new ArrayList<>();
        for (Pair pair : states) {
            if (pair.key().equals(chosen.state())) {
                continue;
            }
            boolean androidHasIt = AndroidLocatorMatcher.matches(field.androidLocator(), pair.androidTree()).orElse(false);
            if (!androidHasIt) {
                continue;
            }
            IosLocatorValidator.Verdict verdict = IosLocatorValidator.validate(pair.iosTree(), chosen.strategy(),
                    chosen.value(), field.collection());
            if (!verdict.isValid()) {
                conflicts.add(new LocatorConflict(field.field(), chosen.state(), pair.key(),
                        "the Android locator finds the element in " + pair.key() + " but the chosen iOS locator "
                                + verdict.problem().orElse("")));
            }
        }
        return conflicts;
    }

    /** States with both snapshots, DEFAULT first, then by state and variant. */
    private List<Pair> statePairs(ScreenId screen) {
        Set<ScreenStateKey> keys = new HashSet<>(snapshots.findKeys(screen, Platform.ANDROID));
        keys.retainAll(snapshots.findKeys(screen, Platform.IOS));
        List<ScreenStateKey> ordered = keys.stream().sorted(Comparator
                .comparing((ScreenStateKey k) -> k.state().ordinal()).thenComparing(ScreenStateKey::variant)).toList();
        List<Pair> pairs = new ArrayList<>();
        for (ScreenStateKey key : ordered) {
            Optional<StateSnapshot> android = snapshots.find(key, Platform.ANDROID);
            Optional<StateSnapshot> ios = snapshots.find(key, Platform.IOS);
            if (android.isPresent() && ios.isPresent()) {
                pairs.add(new Pair(key, android.get(), ios.get(), XmlElement.parse(android.get().cleanXml()),
                        XmlElement.parse(ios.get().cleanXml())));
            }
        }
        return pairs;
    }

    private static Optional<IosLocatorStrategy> strategyOf(String text) {
        if (text == null) {
            return Optional.empty();
        }
        for (IosLocatorStrategy strategy : IosLocatorStrategy.values()) {
            if (strategy.annotationAttribute().equalsIgnoreCase(text.strip())
                    || strategy.name().equalsIgnoreCase(text.strip().toUpperCase(Locale.ROOT))) {
                return Optional.of(strategy);
            }
        }
        return Optional.empty();
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    private record Pair(ScreenStateKey key, StateSnapshot android, StateSnapshot ios, List<XmlElement> androidTree,
                        List<XmlElement> iosTree) {
    }

    private record Resolution(ScreenStateKey state, IosLocatorStrategy strategy, String value, Optional<String> scrollText) {
    }
}
