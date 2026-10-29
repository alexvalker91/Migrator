package com.project.migration.application.delta;

import com.project.migration.domain.model.screen.ScreenId;
import com.project.migration.domain.model.screen.ScreenState;
import com.project.migration.domain.model.screen.ScreenStateKey;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Turns the model's {@link DeltaAnalysis} into domain values. Entries that do not hold up are dropped and
 * described in {@code problems}, so one repair round can name exactly what was wrong.
 */
final class DeltaAnalysisValidator {

    private final Set<String> pageObjectClasses;
    private final Set<String> testNames;
    private final Set<ScreenId> knownScreens;

    /**
     * @param pageObjectClasses FQCNs the model may assign a screen to
     * @param testNames         methods the model may report states for
     * @param knownScreens      screens that already exist in the cache
     */
    DeltaAnalysisValidator(Set<String> pageObjectClasses, Set<String> testNames, Set<ScreenId> knownScreens) {
        this.pageObjectClasses = Set.copyOf(pageObjectClasses);
        this.testNames = Set.copyOf(testNames);
        this.knownScreens = Set.copyOf(knownScreens);
    }

    ValidatedAnalysis validate(DeltaAnalysis analysis) {
        List<String> problems = new ArrayList<>();
        Map<String, ScreenId> assignments = new LinkedHashMap<>();
        for (DeltaAnalysis.ScreenAssignment assignment : orEmpty(analysis.screens())) {
            if (!pageObjectClasses.contains(assignment.pageObject())) {
                problems.add("'" + assignment.pageObject() + "' is not one of the listed Page Object classes");
                continue;
            }
            screenId(assignment.screen()).ifPresentOrElse(
                    screen -> assignments.put(assignment.pageObject(), screen),
                    () -> problems.add("'" + assignment.screen() + "' is not a valid screen id for "
                            + assignment.pageObject() + " (letters, digits, _ and - only)"));
        }

        Set<ScreenId> allowedScreens = new LinkedHashSet<>(knownScreens);
        allowedScreens.addAll(assignments.values());
        pageObjectClasses.forEach(fqcn -> screenId(simpleName(fqcn)).ifPresent(allowedScreens::add));

        Map<String, Set<ScreenStateKey>> states = new HashMap<>();
        for (DeltaAnalysis.TestStates test : orEmpty(analysis.tests())) {
            if (!testNames.contains(test.test())) {
                problems.add("'" + test.test() + "' is not one of the listed tests");
                continue;
            }
            Set<ScreenStateKey> keys = states.computeIfAbsent(test.test(), name -> new LinkedHashSet<>());
            for (DeltaAnalysis.StateNeed need : orEmpty(test.states())) {
                stateKey(test.test(), need, allowedScreens, problems).ifPresent(keys::add);
            }
        }
        return new ValidatedAnalysis(assignments, states, problems);
    }

    private static Optional<ScreenStateKey> stateKey(
            String test, DeltaAnalysis.StateNeed need, Set<ScreenId> allowedScreens, List<String> problems) {
        ScreenState state;
        try {
            state = ScreenState.valueOf(need.state() == null ? "" : need.state().strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            problems.add(test + ": '" + need.state() + "' is not a screen state");
            return Optional.empty();
        }
        Optional<ScreenId> screen = screenId(need.screen());
        if (screen.isEmpty() || !allowedScreens.contains(screen.get())) {
            problems.add(test + ": screen '" + need.screen() + "' is neither known nor assigned to a Page Object");
            return Optional.empty();
        }
        String variant = need.variant() == null || need.variant().isBlank()
                ? ScreenStateKey.DEFAULT_VARIANT : need.variant().strip();
        try {
            return Optional.of(new ScreenStateKey(screen.get(), state, variant));
        } catch (IllegalArgumentException e) {
            problems.add(test + ": variant '" + need.variant() + "' is not valid (letters, digits, _ and - only)");
            return Optional.empty();
        }
    }

    private static Optional<ScreenId> screenId(String value) {
        try {
            return Optional.of(new ScreenId(value == null ? "" : value.strip()));
        } catch (IllegalArgumentException | NullPointerException e) {
            return Optional.empty();
        }
    }

    static String simpleName(String fqcn) {
        return fqcn.substring(fqcn.lastIndexOf('.') + 1);
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }
}
