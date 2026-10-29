package com.project.migration.infrastructure.ast;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.project.migration.domain.exception.CodeModificationException;
import com.project.migration.domain.model.analysis.ComplexityMarker;
import com.project.migration.domain.model.analysis.ComplexityMarkerType;
import com.project.migration.domain.model.code.BodyInspection;
import com.project.migration.domain.model.code.FrameworkContract;
import com.project.migration.domain.model.code.MethodRef;

import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Judges a candidate method body before it is written: syntax, leftover Android-specific constructs and calls on
 * {@code this} that cannot be resolved (hallucinated helpers). Nothing is modified.
 */
final class BodyInspector {

    private final ProjectSourceIndex index;
    private final ComplexityMarkerDetector markers;
    private final FrameworkContract framework;

    BodyInspector(ProjectSourceIndex index, ComplexityMarkerDetector markers, FrameworkContract framework) {
        this.index = index;
        this.markers = markers;
        this.framework = framework;
    }

    BodyInspection inspect(MethodRef method, String newBody) {
        if (index.type(method.classFqcn()).isEmpty()) {
            return failed("Class " + method.classFqcn() + " not found in project sources");
        }
        BlockStmt body;
        try {
            body = Snippets.parseBlock(newBody);
        } catch (CodeModificationException e) {
            return failed(e.getMessage());
        }
        Set<ComplexityMarkerType> remaining = markers.detectIn(body, method).stream()
                .map(ComplexityMarker::type).collect(Collectors.toSet());
        return new BodyInspection(Optional.empty(), remaining, invalidCalls(method, body));
    }

    private Set<String> invalidCalls(MethodRef method, BlockStmt body) {
        CompilationUnit unit = index.unitOf(method.classFqcn());
        Set<String> declaredInBody = body.findAll(MethodDeclaration.class).stream()
                .map(MethodDeclaration::getNameAsString).collect(Collectors.toSet());
        Set<String> invalid = new TreeSet<>();
        for (MethodCallExpr call : body.findAll(MethodCallExpr.class)) {
            boolean onThis = call.getScope().isEmpty() || call.getScope().get() instanceof ThisExpr;
            String name = call.getNameAsString();
            if (!onThis || declaredInBody.contains(name) || isStaticallyImported(unit, name)) {
                continue;
            }
            boolean declaredByProjectClass = declaredOutsideFramework(method.classFqcn(), name);
            if (!declaredByProjectClass && !FrameworkContract.HELPER_NAMES.contains(name)) {
                invalid.add(name);
            }
        }
        return invalid;
    }

    private boolean declaredOutsideFramework(String classFqcn, String methodName) {
        return index.hierarchyOf(classFqcn).stream()
                .filter(owner -> !framework.isFrameworkBaseClass(owner))
                .anyMatch(owner -> !index.type(owner).orElseThrow().getMethodsByName(methodName).isEmpty());
    }

    private static boolean isStaticallyImported(CompilationUnit unit, String methodName) {
        for (ImportDeclaration declaration : unit.getImports()) {
            if (!declaration.isStatic()) {
                continue;
            }
            String name = declaration.getNameAsString();
            if (declaration.isAsterisk() || name.endsWith("." + methodName)) {
                return true;
            }
        }
        return false;
    }

    private static BodyInspection failed(String message) {
        return new BodyInspection(Optional.of(message), Set.of(), Set.of());
    }
}
