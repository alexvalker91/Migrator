package com.project.migration.domain.model.code;

import com.project.migration.domain.model.Checks;

import java.util.Set;

/**
 * Merges groups (e.g. {@code android-only}, {@code ios-only}, {@code ios-needs-fix}) into the
 * {@code @Test(groups = ...)} attribute of a test method, keeping existing groups.
 */
public record AddTestGroups(TestMethodRef test, Set<String> groups) implements CodeChange {

    public static final String ANDROID_ONLY = "android-only";
    public static final String IOS_ONLY = "ios-only";
    public static final String IOS_NEEDS_FIX = "ios-needs-fix";

    public AddTestGroups {
        Checks.notNull(test, "test");
        groups = Set.copyOf(Checks.notNull(groups, "groups"));
        if (groups.isEmpty()) {
            throw new IllegalArgumentException("groups must not be empty");
        }
        groups.forEach(group -> Checks.notBlank(group, "group"));
    }

    @Override
    public String targetClassFqcn() {
        return test.classFqcn();
    }
}
