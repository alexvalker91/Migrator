package com.project.migration.domain.model.run;

import com.project.migration.domain.model.Checks;

import java.nio.file.Path;

/**
 * One compiler error reported by {@code mvn test-compile}.
 */
public record CompilationError(Path file, int line, String message) {

    public CompilationError {
        Checks.notNull(file, "file");
        Checks.nonNegative(line, "line");
        Checks.notBlank(message, "message");
    }
}
