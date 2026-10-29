package com.project.migration.domain.model.code;

/**
 * Helpers for Java type names.
 */
public final class CodeNames {

    private CodeNames() {
    }

    public static String simpleName(String fqcn) {
        int dot = fqcn.lastIndexOf('.');
        return dot < 0 ? fqcn : fqcn.substring(dot + 1);
    }
}
