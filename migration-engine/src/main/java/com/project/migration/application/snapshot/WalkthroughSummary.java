package com.project.migration.application.snapshot;

import java.util.List;

/**
 * Structured answer of the walkthrough summary call; converted to the domain's {@code AndroidWalkthrough}.
 */
public record WalkthroughSummary(List<Action> steps, List<Observation> observations) {

    /** @param action one of TAP, TYPE, SCROLL, SWIPE, PRESS_BACK, WAIT, OTHER */
    public record Action(String action, String target, String value) {
    }

    public record Observation(String screen, String state, String variant, List<String> visibleTexts) {
    }
}
