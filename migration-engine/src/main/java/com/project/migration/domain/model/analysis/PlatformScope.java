package com.project.migration.domain.model.analysis;

/**
 * Platforms a test is meant to run on after migration.
 */
public enum PlatformScope {
    BOTH,
    ANDROID_ONLY,
    IOS_ONLY;

    public boolean includesIos() {
        return this != ANDROID_ONLY;
    }
}
