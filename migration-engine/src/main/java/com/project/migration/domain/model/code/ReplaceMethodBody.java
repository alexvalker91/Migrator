package com.project.migration.domain.model.code;

import com.project.migration.domain.model.Checks;

/**
 * Replaces the body of a method with LLM-produced source ({@code { ... }}). The body must parse as a valid
 * Java block before anything is written to disk.
 */
public record ReplaceMethodBody(MethodRef method, String newBody) implements CodeChange {

    public ReplaceMethodBody {
        Checks.notNull(method, "method");
        Checks.notBlank(newBody, "newBody");
    }

    @Override
    public String targetClassFqcn() {
        return method.classFqcn();
    }
}
