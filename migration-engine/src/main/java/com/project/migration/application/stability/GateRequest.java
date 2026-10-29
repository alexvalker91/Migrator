package com.project.migration.application.stability;

import com.project.migration.domain.model.Checks;
import com.project.migration.domain.model.analysis.TestMethodTask;
import com.project.migration.domain.model.screen.ScreenId;
import com.project.migration.domain.model.vcs.CommitId;

import java.util.Map;

/**
 * One test to stabilize.
 *
 * @param screenOfClass screen of each Page Object class (from the Stage 1 delta), used to mark the cache
 * @param baseline      last commit: the point failed healing returns to
 */
public record GateRequest(TestMethodTask task, Map<String, ScreenId> screenOfClass, CommitId baseline) {

    public GateRequest {
        Checks.notNull(task, "task");
        screenOfClass = Map.copyOf(Checks.notNull(screenOfClass, "screenOfClass"));
        Checks.notNull(baseline, "baseline");
    }
}
