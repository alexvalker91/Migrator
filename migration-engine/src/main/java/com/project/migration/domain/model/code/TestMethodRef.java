package com.project.migration.domain.model.code;

import com.project.migration.domain.model.Checks;

/**
 * Reference to a TestNG {@code @Test} method. TestNG test methods are addressed by name only
 * (data-provider invocations share one reference).
 */
public record TestMethodRef(String classFqcn, String methodName) {

    public TestMethodRef {
        Checks.notBlank(classFqcn, "classFqcn");
        Checks.notBlank(methodName, "methodName");
    }

    public String simpleClassName() {
        return CodeNames.simpleName(classFqcn);
    }

    /** {@code com.app.LoginTest#loginNegativeTest} */
    public String key() {
        return classFqcn + "#" + methodName;
    }

    @Override
    public String toString() {
        return key();
    }
}
