package com.project.migration.domain.model.codegen;

import com.project.migration.domain.model.Checks;
import com.project.migration.domain.model.code.MethodRef;
import com.project.migration.domain.model.code.ModificationResult;
import com.project.migration.domain.model.code.TestMethodRef;

import java.util.List;

/**
 * Result of Stage 3 (without compilation and commit, which the orchestrator does).
 *
 * @param modification    files actually rewritten
 * @param mapped          iOS locators written to Page Objects
 * @param unresolved      locator fields without an iOS locator
 * @param conflicts       fields whose locator is ambiguous across states
 * @param adaptedMethods  methods rewritten by Tier-1
 * @param needsManual     methods whose adaptation was rejected
 * @param taggedTests     tests that received platform groups
 */
public record CodeAdaptationResult(
        ModificationResult modification,
        List<MappedLocator> mapped,
        List<UnresolvedLocator> unresolved,
        List<LocatorConflict> conflicts,
        List<MethodRef> adaptedMethods,
        List<ManualAdaptation> needsManual,
        List<TestMethodRef> taggedTests) {

    public CodeAdaptationResult {
        Checks.notNull(modification, "modification");
        mapped = List.copyOf(Checks.notNull(mapped, "mapped"));
        unresolved = List.copyOf(Checks.notNull(unresolved, "unresolved"));
        conflicts = List.copyOf(Checks.notNull(conflicts, "conflicts"));
        adaptedMethods = List.copyOf(Checks.notNull(adaptedMethods, "adaptedMethods"));
        needsManual = List.copyOf(Checks.notNull(needsManual, "needsManual"));
        taggedTests = List.copyOf(Checks.notNull(taggedTests, "taggedTests"));
    }

    public boolean hasOpenIssues() {
        return !unresolved.isEmpty() || !conflicts.isEmpty() || !needsManual.isEmpty();
    }
}
