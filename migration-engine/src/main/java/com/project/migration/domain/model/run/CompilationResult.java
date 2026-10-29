package com.project.migration.domain.model.run;

import com.project.migration.domain.model.Checks;

import java.time.Duration;
import java.util.List;

/**
 * Result of compiling the target project (main + test sources).
 */
public record CompilationResult(boolean success, List<CompilationError> errors, Duration duration) {

    public CompilationResult {
        errors = List.copyOf(Checks.notNull(errors, "errors"));
        Checks.notNull(duration, "duration");
        if (success && !errors.isEmpty()) {
            throw new IllegalArgumentException("successful compilation must not report errors");
        }
    }

    public static CompilationResult succeeded(Duration duration) {
        return new CompilationResult(true, List.of(), duration);
    }

    public static CompilationResult failed(List<CompilationError> errors, Duration duration) {
        return new CompilationResult(false, errors, duration);
    }
}
