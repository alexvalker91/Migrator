package com.project.migration.application.healing;

import java.util.List;

/**
 * Structured answer of a compile-repair call.
 */
public record CompileFix(String explanation, List<HealingProposal.Change> changes) {
}
