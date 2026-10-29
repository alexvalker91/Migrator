package com.project.migration.infrastructure.ast;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.project.migration.domain.exception.CodeModificationException;
import com.project.migration.domain.model.code.ReplaceMethodBody;

import java.util.List;

/**
 * Replaces a method body with generated code that must parse as a Java block. The new body is printed in the
 * standard layout and indented to the method; the rest of the file is untouched.
 */
final class ReplaceMethodBodyApplier implements ChangeApplier<ReplaceMethodBody> {

    @Override
    public List<SourceText.Edit> edits(CompilationUnit unit, SourceText source, TypeDeclaration<?> type,
                                       ReplaceMethodBody change) {
        MethodDeclaration method = SourceLookup.findMethod(type, change.method()).orElseThrow(() ->
                new CodeModificationException("Method " + change.method() + " not found"));
        BlockStmt current = method.getBody().orElseThrow(() ->
                new CodeModificationException("Method " + change.method() + " is abstract; it has no body to replace"));
        BlockStmt replacement = Snippets.parseBlock(change.newBody());
        if (Snippets.normalized(current).equals(Snippets.normalized(replacement))) {
            return List.of();
        }
        String indentation = source.indentationAt(source.begin(method));
        return List.of(new SourceText.Edit(source.begin(current), source.end(current),
                source.indentContinuation(replacement.toString(), indentation)));
    }
}
