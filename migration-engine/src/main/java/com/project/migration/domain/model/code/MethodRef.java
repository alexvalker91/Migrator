package com.project.migration.domain.model.code;

import com.project.migration.domain.model.Checks;

import java.util.List;

/**
 * Stable reference to a Page Object / step method. Parameter types (simple or qualified names as written in
 * source) disambiguate overloads; the reference is used as a cache key across runs.
 */
public record MethodRef(String classFqcn, String methodName, List<String> parameterTypes) {

    public MethodRef {
        Checks.notBlank(classFqcn, "classFqcn");
        Checks.notBlank(methodName, "methodName");
        parameterTypes = List.copyOf(Checks.notNull(parameterTypes, "parameterTypes"));
    }

    public static MethodRef of(String classFqcn, String methodName, String... parameterTypes) {
        return new MethodRef(classFqcn, methodName, List.of(parameterTypes));
    }

    public String simpleClassName() {
        return CodeNames.simpleName(classFqcn);
    }

    /** {@code com.app.LoginPage#enterEmail(String)} */
    public String key() {
        return classFqcn + "#" + methodName + "(" + String.join(",", parameterTypes) + ")";
    }

    @Override
    public String toString() {
        return key();
    }
}
