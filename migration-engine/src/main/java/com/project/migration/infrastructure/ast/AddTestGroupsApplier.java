package com.project.migration.infrastructure.ast;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MarkerAnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.project.migration.domain.exception.CodeModificationException;
import com.project.migration.domain.model.code.AddTestGroups;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Merges groups into {@code @Test(groups = {...})}: existing entries (literals or constants) keep their
 * source text, missing literals are appended in sorted order. {@code @Test} without attributes becomes
 * {@code @Test(groups = {...})}; other attributes are not touched.
 */
final class AddTestGroupsApplier implements ChangeApplier<AddTestGroups> {

    private static final String GROUPS = "groups";

    @Override
    public List<SourceText.Edit> edits(CompilationUnit unit, SourceText source, TypeDeclaration<?> type,
                                       AddTestGroups change) {
        MethodDeclaration method = type.getMethodsByName(change.test().methodName()).stream().findFirst()
                .orElseThrow(() -> new CodeModificationException("Test method " + change.test() + " not found"));
        AnnotationExpr test = TestMethodScanner.testAnnotation(method).orElseThrow(() ->
                new CodeModificationException(change.test() + " is not annotated with TestNG @Test"));

        Optional<MemberValuePair> pair = test instanceof NormalAnnotationExpr normal
                ? normal.getPairs().stream().filter(p -> p.getNameAsString().equals(GROUPS)).findFirst()
                : Optional.empty();
        List<Expression> values = pair.map(p -> p.getValue() instanceof ArrayInitializerExpr array
                ? List.copyOf(array.getValues()) : List.of(p.getValue())).orElse(List.of());
        List<String> present = values.stream()
                .filter(StringLiteralExpr.class::isInstance)
                .map(value -> ((StringLiteralExpr) value).asString())
                .toList();
        List<String> missing = change.groups().stream().filter(group -> !present.contains(group)).sorted().toList();
        if (missing.isEmpty()) {
            return List.of();
        }

        List<String> merged = new ArrayList<>();
        values.forEach(value -> merged.add(source.sourceOf(value)));
        missing.forEach(group -> merged.add("\"" + new StringLiteralExpr().setString(group).getValue() + "\""));
        String groups = "{" + String.join(", ", merged) + "}";

        if (pair.isPresent()) {
            Expression value = pair.get().getValue();
            return List.of(new SourceText.Edit(source.begin(value), source.end(value), groups));
        }
        return switch (test) {
            case MarkerAnnotationExpr marker -> {
                int at = source.end(marker);
                yield List.of(new SourceText.Edit(at, at, "(" + GROUPS + " = " + groups + ")"));
            }
            case NormalAnnotationExpr normal -> {
                int closingParenthesis = source.end(normal) - 1;
                String prefix = normal.getPairs().isEmpty() ? "" : ", ";
                yield List.of(new SourceText.Edit(closingParenthesis, closingParenthesis,
                        prefix + GROUPS + " = " + groups));
            }
            default -> throw new CodeModificationException("Unsupported @Test form on " + change.test() + ": " + test);
        };
    }
}
