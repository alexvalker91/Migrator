package com.project.migration.infrastructure.ast;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.project.migration.domain.model.code.FrameworkContract;
import com.project.migration.domain.model.code.MethodRef;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Transitive call tree of a test: project methods in the order they first execute (depth-first, a call's scope
 * and arguments before the call itself), each once, cycle-safe. Methods declared in the framework base classes
 * (and their project superclasses) are excluded: they are cross-platform already.
 */
final class CallTreeResolver {

    private static final Comparator<Node> SOURCE_ORDER = Comparator.comparing(
            node -> node.getBegin().orElse(null), Comparator.nullsLast(Comparator.naturalOrder()));

    private final ProjectSourceIndex index;
    private final CallTargetResolver targets;
    private final FrameworkContract framework;

    CallTreeResolver(ProjectSourceIndex index, CallTargetResolver targets, FrameworkContract framework) {
        this.index = index;
        this.targets = targets;
        this.framework = framework;
    }

    List<MethodRef> resolve(MethodDeclaration entryPoint) {
        Set<String> frameworkClasses = frameworkClasses();
        LinkedHashSet<MethodRef> visited = new LinkedHashSet<>();
        visit(entryPoint, frameworkClasses, visited);
        return List.copyOf(visited);
    }

    private void visit(MethodDeclaration method, Set<String> frameworkClasses, Set<MethodRef> visited) {
        if (method.getBody().isEmpty()) {
            return;
        }
        for (MethodCallExpr call : callsInExecutionOrder(method.getBody().get())) {
            for (MethodRef target : targets.targets(call)) {
                if (frameworkClasses.contains(target.classFqcn()) || !visited.add(target)) {
                    continue;
                }
                declaration(target).ifPresent(callee -> visit(callee, frameworkClasses, visited));
            }
        }
    }

    private Optional<MethodDeclaration> declaration(MethodRef ref) {
        return index.type(ref.classFqcn()).flatMap(type -> SourceLookup.findMethod(type, ref));
    }

    private Set<String> frameworkClasses() {
        Set<String> classes = new HashSet<>();
        classes.addAll(index.hierarchyOf(framework.basePageFqcn()));
        classes.addAll(index.hierarchyOf(framework.baseTestFqcn()));
        return classes;
    }

    static List<MethodCallExpr> callsInExecutionOrder(Node root) {
        List<MethodCallExpr> calls = new ArrayList<>();
        collect(root, calls);
        return calls;
    }

    private static void collect(Node node, List<MethodCallExpr> calls) {
        if (node instanceof MethodCallExpr call) {
            call.getScope().ifPresent(scope -> collect(scope, calls));
            call.getArguments().forEach(argument -> collect(argument, calls));
            calls.add(call);
            return;
        }
        List<Node> children = new ArrayList<>(node.getChildNodes());
        children.sort(SOURCE_ORDER);
        children.forEach(child -> collect(child, calls));
    }
}
