package com.project.migration.infrastructure.ast;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SuperExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.types.ResolvedType;
import com.project.migration.domain.model.code.MethodRef;

import java.util.List;
import java.util.Optional;

/**
 * Resolves which project methods a call may invoke. The symbol solver is tried first; when it fails (types of
 * the target project's dependencies are unknown to the engine) the scope type is derived from declarations and
 * the method is looked up by name and arity along the project class hierarchy. Overloads that cannot be told
 * apart that way are all returned.
 */
final class CallTargetResolver {

    private final ProjectSourceIndex index;

    CallTargetResolver(ProjectSourceIndex index) {
        this.index = index;
    }

    List<MethodRef> targets(MethodCallExpr call) {
        Optional<MethodRef> resolved = resolveWithSymbolSolver(call);
        if (resolved.isPresent()) {
            return List.of(resolved.get());
        }
        Optional<String> scopeType = call.getScope().isPresent()
                ? typeOf(call.getScope().get())
                : SourceLookup.enclosingType(call).flatMap(TypeDeclaration::getFullyQualifiedName);
        return scopeType.map(type -> lookup(type, call.getNameAsString(), call.getArguments().size()))
                .orElse(List.of());
    }

    private Optional<MethodRef> resolveWithSymbolSolver(MethodCallExpr call) {
        try {
            ResolvedMethodDeclaration method = call.resolve();
            String owner = method.declaringType().getQualifiedName();
            Optional<TypeDeclaration<?>> type = index.type(owner);
            if (type.isEmpty()) {
                return Optional.empty();
            }
            List<MethodDeclaration> candidates =
                    SourceLookup.methodsByNameAndArity(type.get(), method.getName(), method.getNumberOfParams());
            if (candidates.size() == 1) {
                return Optional.of(SourceLookup.refOf(owner, candidates.getFirst()));
            }
            for (MethodDeclaration candidate : candidates) {
                if (sameParameterTypes(candidate, method)) {
                    return Optional.of(SourceLookup.refOf(owner, candidate));
                }
            }
            return Optional.empty();
        } catch (RuntimeException unresolvable) {
            return Optional.empty();
        }
    }

    private List<MethodRef> lookup(String typeFqcn, String name, int arity) {
        for (String owner : index.hierarchyOf(typeFqcn)) {
            TypeDeclaration<?> type = index.type(owner).orElseThrow();
            List<MethodDeclaration> matches = SourceLookup.methodsByNameAndArity(type, name, arity);
            if (!matches.isEmpty()) {
                return matches.stream().map(method -> SourceLookup.refOf(owner, method)).toList();
            }
        }
        return List.of();
    }

    /** Project type of an expression, if it can be determined. */
    Optional<String> typeOf(Expression expression) {
        Optional<String> solved = solvedType(expression);
        if (solved.isPresent()) {
            return solved;
        }
        return switch (expression) {
            case EnclosedExpr enclosed -> typeOf(enclosed.getInner());
            case CastExpr cast -> resolveWritten(cast, cast.getType().asString());
            case ObjectCreationExpr creation -> resolveWritten(creation, creation.getType().getNameWithScope());
            case ThisExpr thisExpr -> SourceLookup.enclosingType(thisExpr).flatMap(TypeDeclaration::getFullyQualifiedName);
            case SuperExpr superExpr -> SourceLookup.enclosingType(superExpr)
                    .flatMap(TypeDeclaration::getFullyQualifiedName)
                    .flatMap(index::superclassOf);
            case NameExpr name -> variableType(name, name.getNameAsString())
                    .or(() -> resolveWritten(name, name.getNameAsString()));
            case FieldAccessExpr access when access.getScope() instanceof ThisExpr ->
                    fieldType(access, access.getNameAsString());
            case MethodCallExpr call -> targets(call).stream().findFirst().flatMap(this::returnType);
            default -> Optional.empty();
        };
    }

    private Optional<String> solvedType(Expression expression) {
        try {
            ResolvedType type = expression.calculateResolvedType();
            if (type.isReferenceType()) {
                String qualified = type.asReferenceType().getQualifiedName();
                return index.type(qualified).isPresent() ? Optional.of(qualified) : Optional.empty();
            }
            return Optional.empty();
        } catch (RuntimeException unresolvable) {
            return Optional.empty();
        }
    }

    private Optional<String> variableType(Node usage, String name) {
        Optional<String> local = SourceLookup.localVariableType(usage, name);
        if (local.isPresent()) {
            return resolveWritten(usage, local.get());
        }
        return fieldType(usage, name);
    }

    private Optional<String> fieldType(Node usage, String name) {
        Optional<String> enclosing = SourceLookup.enclosingType(usage).flatMap(TypeDeclaration::getFullyQualifiedName);
        if (enclosing.isEmpty()) {
            return Optional.empty();
        }
        for (String owner : index.hierarchyOf(enclosing.get())) {
            Optional<FieldDeclaration> field = SourceLookup.findField(index.type(owner).orElseThrow(), name);
            if (field.isPresent()) {
                String written = field.get().getVariables().stream()
                        .filter(variable -> variable.getNameAsString().equals(name))
                        .findFirst().orElseThrow().getType().asString();
                return index.resolveTypeName(index.unitOf(owner), written);
            }
        }
        return Optional.empty();
    }

    private Optional<String> returnType(MethodRef method) {
        return index.type(method.classFqcn())
                .flatMap(type -> SourceLookup.findMethod(type, method))
                .flatMap(declaration -> index.resolveTypeName(index.unitOf(method.classFqcn()),
                        declaration.getType().asString()));
    }

    private Optional<String> resolveWritten(Node context, String writtenName) {
        Optional<CompilationUnit> unit = context.findCompilationUnit();
        return unit.flatMap(cu -> index.resolveTypeName(cu, writtenName));
    }

    private static boolean sameParameterTypes(MethodDeclaration candidate, ResolvedMethodDeclaration method) {
        for (int i = 0; i < method.getNumberOfParams(); i++) {
            String resolved = SourceLookup.simpleName(ProjectSourceIndex.stripGenerics(method.getParam(i).describeType()));
            String written = SourceLookup.simpleName(ProjectSourceIndex.stripGenerics(
                    SourceLookup.writtenType(candidate.getParameter(i))));
            if (!resolved.equals(written)) {
                return false;
            }
        }
        return true;
    }
}
