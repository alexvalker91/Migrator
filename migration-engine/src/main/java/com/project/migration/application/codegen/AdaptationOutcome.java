package com.project.migration.application.codegen;

import com.project.migration.domain.model.code.CodeChange;
import com.project.migration.domain.model.code.MethodRef;
import com.project.migration.domain.model.codegen.ManualAdaptation;

import java.util.List;

/**
 * Result of the Tier-1 adaptation of Android-specific methods.
 *
 * @param changes  {@code ReplaceMethodBody} and {@code AddImport} changes for the accepted rewrites
 * @param adapted  methods with an accepted rewrite
 * @param manual   methods whose rewrite was rejected
 */
record AdaptationOutcome(List<CodeChange> changes, List<MethodRef> adapted, List<ManualAdaptation> manual) {
}
