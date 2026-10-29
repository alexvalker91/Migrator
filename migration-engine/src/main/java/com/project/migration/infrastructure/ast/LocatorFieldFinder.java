package com.project.migration.infrastructure.ast;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.project.migration.domain.model.code.FieldRef;
import com.project.migration.domain.model.code.LocatorField;
import com.project.migration.domain.model.code.MethodRef;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Page Factory locator fields ({@code @AndroidFindBy}, {@code @AndroidFindBys}, {@code @AndroidFindAll}) a
 * method reads directly, in order of first use. Parameters and locals shadowing a field are not field uses.
 */
final class LocatorFieldFinder {

    static final List<String> ANDROID_ANNOTATIONS = List.of("AndroidFindBy", "AndroidFindBys", "AndroidFindAll");
    static final List<String> IOS_ANNOTATIONS = List.of("iOSXCUITFindBy", "iOSXCUITFindBys", "iOSXCUITFindAll");

    private final ProjectSourceIndex index;

    LocatorFieldFinder(ProjectSourceIndex index) {
        this.index = index;
    }

    List<LocatorField> find(MethodRef ref) {
        Optional<MethodDeclaration> method = index.type(ref.classFqcn()).flatMap(type -> SourceLookup.findMethod(type, ref));
        if (method.isEmpty() || method.get().getBody().isEmpty()) {
            return List.of();
        }
        Map<String, LocatorField> fields = new LinkedHashMap<>();
        for (Node usage : fieldUsages(method.get())) {
            String name = usage instanceof NameExpr nameExpr ? nameExpr.getNameAsString()
                    : ((FieldAccessExpr) usage).getNameAsString();
            if (!fields.containsKey(name)) {
                locatorField(ref.classFqcn(), name).ifPresent(field -> fields.put(name, field));
            }
        }
        return List.copyOf(fields.values());
    }

    /** Locator field {@code fieldName} visible in {@code classFqcn} (declared there or in a project superclass). */
    Optional<LocatorField> locatorField(String classFqcn, String fieldName) {
        for (String owner : index.hierarchyOf(classFqcn)) {
            TypeDeclaration<?> type = index.type(owner).orElseThrow();
            Optional<FieldDeclaration> field = SourceLookup.findField(type, fieldName);
            if (field.isPresent()) {
                return SourceLookup.annotation(field.get(), ANDROID_ANNOTATIONS).map(android -> new LocatorField(
                        new FieldRef(owner, fieldName),
                        sourceForm(android),
                        SourceLookup.annotation(field.get(), IOS_ANNOTATIONS).map(LocatorFieldFinder::sourceForm)));
            }
        }
        return Optional.empty();
    }

    private static List<Node> fieldUsages(MethodDeclaration method) {
        List<Node> usages = new ArrayList<>();
        method.getBody().orElseThrow().walk(Node.TreeTraversal.PREORDER, node -> {
            if (node instanceof NameExpr name
                    && !SourceLookup.isLocalVariableOrParameter(name, name.getNameAsString())) {
                usages.add(name);
            } else if (node instanceof FieldAccessExpr access && access.getScope() instanceof ThisExpr) {
                usages.add(access);
            }
        });
        return usages;
    }

    /**
     * {@code @AndroidFindBy(accessibility = "x")} → {@code accessibility = "x"}; container annotations
     * ({@code @AndroidFindBys}, {@code @AndroidFindAll}) are kept whole.
     */
    static String sourceForm(AnnotationExpr annotation) {
        String simpleName = SourceLookup.simpleName(annotation.getNameAsString());
        if (annotation instanceof NormalAnnotationExpr normal && simpleName.endsWith("FindBy")) {
            return normal.getPairs().stream().map(Node::toString).collect(Collectors.joining(", "));
        }
        return annotation.toString();
    }
}
