package com.project.migration.domain.model.code;

import com.project.migration.domain.model.Checks;

/**
 * Adds an import (no-op when already present).
 *
 * @param importName fully-qualified type, or a static member for {@code isStatic = true}
 */
public record AddImport(String targetClassFqcn, String importName, boolean isStatic) implements CodeChange {

    public AddImport {
        Checks.notBlank(targetClassFqcn, "targetClassFqcn");
        Checks.notBlank(importName, "importName");
    }
}
