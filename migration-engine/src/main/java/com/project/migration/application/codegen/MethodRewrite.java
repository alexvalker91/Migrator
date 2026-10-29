package com.project.migration.application.codegen;

import java.util.List;

/**
 * Structured answer of a method-adaptation call.
 *
 * @param imports fully qualified class names the new body needs
 */
public record MethodRewrite(String newBody, List<String> imports, String explanation) {
}
