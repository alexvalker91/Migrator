package com.project.migration.infrastructure.ast;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.project.migration.domain.model.code.MethodRef;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Lookups shared by the analyzer and the modifier: methods by {@link MethodRef}, fields, annotations.
 * Parameter types are compared by simple name, so {@code String} and {@code java.lang.String} match.
 */
final class SourceLookup {

    private static final Pattern PACKAGE_QUALIFIER = Pattern.compile("\\b[a-z_][\\w]*\\.");

    private SourceLookup() {
    }

    static MethodRef refOf(String classFqcn, MethodDeclaration method) {
        return new MethodRef(classFqcn, method.getNameAsString(),
                method.getParameters().stream().map(SourceLookup::writtenType).toList());
    }

    static Optional<MethodDeclaration> findMethod(TypeDeclaration<?> type, MethodRef ref) {
        return type.getMethodsByName(ref.methodName()).stream()
                .filter(method -> parametersMatch(method, ref.parameterTypes()))
                .findFirst();
    }

    static List<MethodDeclaration> methodsByNameAndArity(TypeDeclaration<?> type, String name, int arity) {
        return type.getMethodsByName(name).stream()
                .filter(method -> acceptsArity(method, arity))
                .toList();
    }

    static Optional<FieldDeclaration> findField(TypeDeclaration<?> type, String fieldName) {
        return type.getFieldByName(fieldName);
    }

    static Optional<AnnotationExpr> annotation(NodeWithAnnotations<?> node, List<String> simpleNames) {
        return node.getAnnotations().stream()
                .filter(annotation -> simpleNames.contains(simpleName(annotation.getNameAsString())))
                .findFirst();
    }

    static List<AnnotationExpr> annotations(NodeWithAnnotations<?> node, List<String> simpleNames) {
        return node.getAnnotations().stream()
                .filter(annotation -> simpleNames.contains(simpleName(annotation.getNameAsString())))
                .toList();
    }

    static Optional<TypeDeclaration<?>> enclosingType(Node node) {
        Optional<Node> current = node.getParentNode();
        while (current.isPresent()) {
            if (current.get() instanceof TypeDeclaration<?> type) {
                return Optional.of(type);
            }
            current = current.get().getParentNode();
        }
        return Optional.empty();
    }

    static Optional<CallableDeclaration<?>> enclosingCallable(Node node) {
        return node.findAncestor(CallableDeclaration.class).map(callable -> (CallableDeclaration<?>) callable);
    }

    /** Declared type of a parameter or local variable named {@code name} visible in the enclosing callable. */
    static Optional<String> localVariableType(Node usage, String name) {
        Optional<CallableDeclaration<?>> callable = enclosingCallable(usage);
        if (callable.isEmpty()) {
            return Optional.empty();
        }
        for (Parameter parameter : callable.get().getParameters()) {
            if (parameter.getNameAsString().equals(name)) {
                return Optional.of(writtenType(parameter));
            }
        }
        return callable.get().findAll(VariableDeclarator.class).stream()
                .filter(variable -> variable.getNameAsString().equals(name))
                .filter(variable -> variable.getParentNode().filter(FieldDeclaration.class::isInstance).isEmpty())
                .filter(variable -> isDeclaredBefore(variable, usage))
                .map(variable -> variable.getType().asString())
                .findFirst();
    }

    static boolean isLocalVariableOrParameter(Node usage, String name) {
        return localVariableType(usage, name).isPresent();
    }

    static String simpleName(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(dot + 1);
    }

    static String writtenType(Parameter parameter) {
        return parameter.getType().asString() + (parameter.isVarArgs() ? "..." : "");
    }

    private static boolean parametersMatch(MethodDeclaration method, List<String> expected) {
        if (method.getParameters().size() != expected.size()) {
            return false;
        }
        for (int i = 0; i < expected.size(); i++) {
            if (!unqualified(writtenType(method.getParameter(i))).equals(unqualified(expected.get(i)))) {
                return false;
            }
        }
        return true;
    }

    private static boolean acceptsArity(MethodDeclaration method, int arity) {
        int declared = method.getParameters().size();
        boolean varArgs = declared > 0 && method.getParameter(declared - 1).isVarArgs();
        return varArgs ? arity >= declared - 1 : arity == declared;
    }

    private static boolean isDeclaredBefore(Node declaration, Node usage) {
        return declaration.getBegin().isEmpty() || usage.getBegin().isEmpty()
                || declaration.getBegin().get().isBefore(usage.getBegin().get());
    }

    private static String unqualified(String typeName) {
        return PACKAGE_QUALIFIER.matcher(typeName.replace(" ", "")).replaceAll("");
    }
}
