package com.project.migration.domain.model.cache;

import com.project.migration.domain.model.Checks;
import com.project.migration.domain.model.code.MethodRef;
import com.project.migration.domain.model.code.TestMethodRef;

import java.time.Instant;
import java.util.Optional;

/**
 * Cached verification state of a Page Object / step method.
 *
 * @param verifiedBy test whose 3/3 PASS verified the step
 */
public record StepCacheEntry(MethodRef step, VerificationStatus status, Optional<TestMethodRef> verifiedBy, Instant updatedAt) {

    public StepCacheEntry {
        Checks.notNull(step, "step");
        Checks.notNull(status, "status");
        verifiedBy = verifiedBy == null ? Optional.empty() : verifiedBy;
        Checks.notNull(updatedAt, "updatedAt");
        if (status == VerificationStatus.VERIFIED_STABLE && verifiedBy.isEmpty()) {
            throw new IllegalArgumentException("a VERIFIED_STABLE step must name the test that verified it");
        }
    }

    public static StepCacheEntry verified(MethodRef step, TestMethodRef by, Instant now) {
        return new StepCacheEntry(step, VerificationStatus.VERIFIED_STABLE, Optional.of(by), now);
    }
}
