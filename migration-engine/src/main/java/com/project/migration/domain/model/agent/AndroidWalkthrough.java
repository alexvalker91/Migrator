package com.project.migration.domain.model.agent;

import com.project.migration.domain.model.Checks;
import com.project.migration.domain.model.code.TestMethodRef;

import java.util.List;

/**
 * Typed record of how the Android reference reached the screen states of a test. It is handed to the iOS pass
 * as context, so iOS repeats the same actions instead of improvising, and is stored with the class report.
 */
public record AndroidWalkthrough(TestMethodRef test, List<WalkthroughStep> steps, List<StateObservation> observations) {

    public AndroidWalkthrough {
        Checks.notNull(test, "test");
        steps = List.copyOf(Checks.notNull(steps, "steps"));
        observations = List.copyOf(Checks.notNull(observations, "observations"));
    }
}
