package com.project.migration.infrastructure.ast;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.project.migration.domain.model.code.TestMethodRef;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * TestNG test discovery: methods annotated with {@code @Test}, plus the public methods of a class annotated
 * with {@code @Test} at class level (TestNG semantics). Groups combine class- and method-level {@code @Test};
 * group constants ({@code Groups.REGRESSION}) are resolved through project sources.
 */
final class TestMethodScanner {

    static final String TESTNG_PACKAGE = "org.testng.annotations";
    private static final String TEST = "Test";
    private static final List<String> NON_TEST_ANNOTATIONS = List.of(
            "BeforeSuite", "AfterSuite", "BeforeTest", "AfterTest", "BeforeGroups", "AfterGroups",
            "BeforeClass", "AfterClass", "BeforeMethod", "AfterMethod", "DataProvider", "Factory");

    private final ProjectSourceIndex index;

    TestMethodScanner(ProjectSourceIndex index) {
        this.index = index;
    }

    List<TestMethodRef> testMethods(String classFqcn) {
        Optional<TypeDeclaration<?>> type = index.type(classFqcn);
        if (type.isEmpty() || !(type.get() instanceof ClassOrInterfaceDeclaration declaration)
                || declaration.isInterface()) {
            return List.of();
        }
        boolean classLevelTest = testAnnotation(declaration).isPresent();
        return declaration.getMethods().stream()
                .filter(method -> testAnnotation(method).isPresent() || classLevelTest && isImplicitTest(method))
                .map(method -> new TestMethodRef(classFqcn, method.getNameAsString()))
                .distinct()
                .toList();
    }

    List<TestMethodRef> allTestMethods() {
        return index.types().stream()
                .filter(type -> type instanceof ClassOrInterfaceDeclaration declaration && !declaration.isAbstract())
                .flatMap(type -> type.getFullyQualifiedName().stream())
                .flatMap(fqcn -> testMethods(fqcn).stream())
                .toList();
    }

    Optional<MethodDeclaration> declaration(TestMethodRef test) {
        return index.type(test.classFqcn())
                .flatMap(type -> type.getMethodsByName(test.methodName()).stream().findFirst());
    }

    Set<String> groups(TestMethodRef test) {
        Optional<TypeDeclaration<?>> type = index.type(test.classFqcn());
        Optional<MethodDeclaration> method = declaration(test);
        if (type.isEmpty() || method.isEmpty()) {
            return Set.of();
        }
        Set<String> groups = new LinkedHashSet<>();
        testAnnotation(type.get()).ifPresent(annotation -> groups.addAll(groupsOf(annotation)));
        testAnnotation(method.get()).ifPresent(annotation -> groups.addAll(groupsOf(annotation)));
        return Set.copyOf(groups);
    }

    static Optional<AnnotationExpr> testAnnotation(NodeWithAnnotations<?> node) {
        return node.getAnnotations().stream().filter(TestMethodScanner::isTestNgTest).findFirst();
    }

    static boolean isTestNgTest(AnnotationExpr annotation) {
        String name = annotation.getNameAsString();
        if (name.equals(TESTNG_PACKAGE + "." + TEST)) {
            return true;
        }
        return name.equals(TEST) && annotation.findCompilationUnit().map(TestMethodScanner::importsTestNgTest).orElse(false);
    }

    private static boolean importsTestNgTest(CompilationUnit unit) {
        return unit.getImports().stream().anyMatch(imp -> !imp.isStatic()
                && (imp.getNameAsString().equals(TESTNG_PACKAGE + "." + TEST)
                || imp.isAsterisk() && imp.getNameAsString().equals(TESTNG_PACKAGE)));
    }

    private static boolean isImplicitTest(MethodDeclaration method) {
        return method.isPublic() && !method.isStatic() && !method.isAbstract()
                && SourceLookup.annotation(method, NON_TEST_ANNOTATIONS).isEmpty();
    }

    private Set<String> groupsOf(AnnotationExpr annotation) {
        if (!(annotation instanceof NormalAnnotationExpr normal)) {
            return Set.of();
        }
        Set<String> groups = new LinkedHashSet<>();
        normal.getPairs().stream()
                .filter(pair -> pair.getNameAsString().equals("groups"))
                .forEach(pair -> {
                    Expression value = pair.getValue();
                    List<Expression> values = value instanceof ArrayInitializerExpr array ? array.getValues() : List.of(value);
                    values.forEach(expression -> constantValue(expression).ifPresent(groups::add));
                });
        return groups;
    }

    /** String literal, or a {@code static final String} constant declared in project sources. */
    Optional<String> constantValue(Expression expression) {
        if (expression instanceof StringLiteralExpr literal) {
            return Optional.of(literal.asString());
        }
        Optional<String> owner;
        String fieldName;
        if (expression instanceof FieldAccessExpr access) {
            owner = expression.findCompilationUnit()
                    .flatMap(unit -> index.resolveTypeName(unit, access.getScope().toString()));
            fieldName = access.getNameAsString();
        } else if (expression instanceof NameExpr name) {
            owner = SourceLookup.enclosingType(name).flatMap(TypeDeclaration::getFullyQualifiedName);
            fieldName = name.getNameAsString();
        } else {
            return Optional.empty();
        }
        return owner.flatMap(index::type)
                .flatMap(type -> SourceLookup.findField(type, fieldName))
                .map(FieldDeclaration::getVariables)
                .flatMap(variables -> variables.stream().filter(v -> v.getNameAsString().equals(fieldName)).findFirst())
                .flatMap(VariableDeclarator::getInitializer)
                .filter(StringLiteralExpr.class::isInstance)
                .map(initializer -> ((StringLiteralExpr) initializer).asString());
    }
}
