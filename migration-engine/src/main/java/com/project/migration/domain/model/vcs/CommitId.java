package com.project.migration.domain.model.vcs;

import com.project.migration.domain.model.Checks;

/**
 * Git commit hash (abbreviated or full).
 */
public record CommitId(String value) {

    public CommitId {
        Checks.notBlank(value, "commit id");
        if (value.length() < 7 || value.length() > 64 || !value.chars().allMatch(CommitId::isHex)) {
            throw new IllegalArgumentException("not a git commit hash: " + value);
        }
    }

    private static boolean isHex(int c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    public String abbreviated() {
        return value.substring(0, 7);
    }

    @Override
    public String toString() {
        return value;
    }
}
