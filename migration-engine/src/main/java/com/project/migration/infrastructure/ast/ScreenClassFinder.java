package com.project.migration.infrastructure.ast;

import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.project.migration.domain.model.code.FrameworkContract;

import java.util.List;

/**
 * Page Object classes: subclasses of the framework's base page class that declare at least one Android locator
 * field. Their number is the project's screen count.
 */
final class ScreenClassFinder {

    private final ProjectSourceIndex index;
    private final FrameworkContract framework;

    ScreenClassFinder(ProjectSourceIndex index, FrameworkContract framework) {
        this.index = index;
        this.framework = framework;
    }

    List<String> classes() {
        return index.types().stream()
                .map(type -> type.getFullyQualifiedName().orElse(""))
                .filter(fqcn -> !fqcn.isEmpty() && !fqcn.equals(framework.basePageFqcn()))
                .filter(fqcn -> index.hierarchyOf(fqcn).contains(framework.basePageFqcn()))
                .filter(fqcn -> hasLocatorField(index.type(fqcn).orElseThrow()))
                .sorted().toList();
    }

    private static boolean hasLocatorField(TypeDeclaration<?> type) {
        for (FieldDeclaration field : type.getFields()) {
            if (SourceLookup.annotation(field, LocatorFieldFinder.ANDROID_ANNOTATIONS).isPresent()) {
                return true;
            }
        }
        return false;
    }
}
