package com.project.migration.application.codegen;

import com.project.migration.domain.model.code.AddIosLocator;
import com.project.migration.domain.model.code.FieldRef;
import com.project.migration.domain.model.codegen.LocatorConflict;
import com.project.migration.domain.model.codegen.MappedLocator;
import com.project.migration.domain.model.codegen.UnresolvedLocator;

import java.util.List;
import java.util.Map;

/**
 * Result of mapping the locators of all screens.
 *
 * @param changes     one {@link AddIosLocator} per mapped field
 * @param scrollTexts text to scroll to before using a mapped field whose iOS element is off screen
 */
public record MappingOutcome(
        List<AddIosLocator> changes,
        List<MappedLocator> mapped,
        List<UnresolvedLocator> unresolved,
        List<LocatorConflict> conflicts,
        Map<FieldRef, String> scrollTexts) {
}
