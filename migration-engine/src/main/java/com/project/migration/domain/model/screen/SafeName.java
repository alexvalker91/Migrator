package com.project.migration.domain.model.screen;

/**
 * Names that end up in cache/snapshot file paths are restricted to {@code [A-Za-z0-9_-]}.
 */
final class SafeName {

    private SafeName() {
    }

    static boolean isSafe(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '-';
            if (!allowed) {
                return false;
            }
        }
        return true;
    }
}
