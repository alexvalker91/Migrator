package com.project.migration.domain.model.code;

import com.project.migration.domain.model.Checks;

/**
 * Stable reference to a locator field of a Page Object.
 */
public record FieldRef(String classFqcn, String fieldName) {

    public FieldRef {
        Checks.notBlank(classFqcn, "classFqcn");
        Checks.notBlank(fieldName, "fieldName");
    }

    public String simpleClassName() {
        return CodeNames.simpleName(classFqcn);
    }

    /** {@code com.app.LoginPage.emailField} */
    public String key() {
        return classFqcn + "." + fieldName;
    }

    @Override
    public String toString() {
        return key();
    }
}
