package com.project.migration.infrastructure.ast;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;

import java.util.ArrayList;
import java.util.List;

/**
 * TestNG setup methods of a test class and its project superclasses, superclass methods first.
 */
final class SetupMethodFinder {

    private static final List<String> SETUP_ANNOTATIONS = List.of("BeforeSuite", "BeforeTest", "BeforeClass", "BeforeMethod");

    private final ProjectSourceIndex index;

    SetupMethodFinder(ProjectSourceIndex index) {
        this.index = index;
    }

    List<String> sources(String classFqcn) {
        List<String> hierarchy = new ArrayList<>(index.hierarchyOf(classFqcn));
        List<String> sources = new ArrayList<>();
        for (int i = hierarchy.size() - 1; i >= 0; i--) {
            TypeDeclaration<?> type = index.type(hierarchy.get(i)).orElseThrow();
            for (MethodDeclaration method : type.getMethods()) {
                if (SourceLookup.annotation(method, SETUP_ANNOTATIONS).isPresent()) {
                    method.getTokenRange().ifPresent(range ->
                            sources.add("// " + type.getNameAsString() + "\n" + range));
                }
            }
        }
        return List.copyOf(sources);
    }
}
