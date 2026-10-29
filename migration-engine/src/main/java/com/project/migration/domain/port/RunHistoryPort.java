package com.project.migration.domain.port;

import com.project.migration.domain.model.history.ClassRunSummary;

import java.util.List;

/**
 * Append-only record of finished class migrations ({@code .migration/history/runs.jsonl}); the scaling projection of
 * the reports is computed from it.
 */
public interface RunHistoryPort {

    /** Durable when it returns. */
    void append(ClassRunSummary summary);

    /** Every recorded run, oldest first; unreadable lines are skipped. */
    List<ClassRunSummary> findAll();
}
