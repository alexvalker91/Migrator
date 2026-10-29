package com.project.migration.application.delta;

import java.util.List;

/**
 * Structured answer of the Tier-3 model in Stage 1. Plain strings on purpose: the model's output is validated
 * against the domain types by {@link DeltaAnalysisValidator} before anything is used.
 *
 * @param screens which screen each Page Object class represents
 * @param tests   screen states each test needs
 */
public record DeltaAnalysis(List<ScreenAssignment> screens, List<TestStates> tests) {

    /** @param pageObject fully-qualified class name */
    public record ScreenAssignment(String pageObject, String screen) {
    }

    /** @param test test method name */
    public record TestStates(String test, List<StateNeed> states) {
    }

    /** @param variant {@code default} when the screen has only one instance of the state */
    public record StateNeed(String screen, String state, String variant) {
    }
}
