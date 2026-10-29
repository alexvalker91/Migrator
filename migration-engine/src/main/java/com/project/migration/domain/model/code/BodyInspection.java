package com.project.migration.domain.model.code;

import com.project.migration.domain.model.Checks;
import com.project.migration.domain.model.analysis.ComplexityMarkerType;

import java.util.Optional;
import java.util.Set;

/**
 * Read-only verdict on a replacement method body, produced without touching any file.
 *
 * @param syntaxError      parser problems if the body is not a valid Java block
 * @param remainingMarkers Android-specific constructs the new body still contains
 * @param invalidCalls     calls on {@code this} that resolve to nothing in the project or to a framework method
 *                         outside the documented helper set
 */
public record BodyInspection(
        Optional<String> syntaxError, Set<ComplexityMarkerType> remainingMarkers, Set<String> invalidCalls) {

    public BodyInspection {
        syntaxError = syntaxError == null ? Optional.empty() : syntaxError;
        remainingMarkers = Set.copyOf(Checks.notNull(remainingMarkers, "remainingMarkers"));
        invalidCalls = Set.copyOf(Checks.notNull(invalidCalls, "invalidCalls"));
    }

    public boolean isAcceptable() {
        return syntaxError.isEmpty() && remainingMarkers.isEmpty() && invalidCalls.isEmpty();
    }

    /** Human-readable reasons for a repair prompt; empty when acceptable. */
    public String problems() {
        StringBuilder text = new StringBuilder();
        syntaxError.ifPresent(error -> text.append("The body does not parse: ").append(error).append('\n'));
        if (!remainingMarkers.isEmpty()) {
            text.append("Android-specific constructs remain: ").append(remainingMarkers).append('\n');
        }
        if (!invalidCalls.isEmpty()) {
            text.append("Calls to methods that do not exist or are not allowed helpers: ").append(invalidCalls)
                    .append('\n');
        }
        return text.toString().strip();
    }
}
