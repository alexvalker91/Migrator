package com.project.migration.domain.model;

/**
 * Mobile platform a test, snapshot or MCP session belongs to.
 */
public enum Platform {
    ANDROID("Android", "android"),
    IOS("iOS", "ios");

    private final String runnerValue;
    private final String fileToken;

    Platform(String runnerValue, String fileToken) {
        this.runnerValue = runnerValue;
        this.fileToken = fileToken;
    }

    /** Value passed to the target project as {@code -Dplatform=<value>} / TestNG {@code platform} parameter. */
    public String runnerValue() {
        return runnerValue;
    }

    /** Lower-case token used in snapshot file names ({@code state_DEFAULT_ios_clean.xml}). */
    public String fileToken() {
        return fileToken;
    }

    public Platform opposite() {
        return this == ANDROID ? IOS : ANDROID;
    }

    public static Platform fromRunnerValue(String value) {
        for (Platform platform : values()) {
            if (platform.runnerValue.equalsIgnoreCase(value)) {
                return platform;
            }
        }
        throw new IllegalArgumentException("Unknown platform: " + value);
    }
}
