package com.project.migration.infrastructure.ast;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.project.migration.domain.exception.CodeModificationException;
import com.project.migration.domain.model.code.InsertCallBefore;

import java.util.List;

/**
 * Inserts a statement before the first statement of the method that matches the anchor structurally
 * (comments and formatting ignored), on its own line with the anchor's indentation. Idempotent: nothing
 * happens if the statement is already right before the anchor.
 */
final class InsertCallBeforeApplier implements ChangeApplier<InsertCallBefore> {

    @Override
    public List<SourceText.Edit> edits(CompilationUnit unit, SourceText source, TypeDeclaration<?> type,
                                       InsertCallBefore change) {
        MethodDeclaration method = SourceLookup.findMethod(type, change.method()).orElseThrow(() ->
                new CodeModificationException("Method " + change.method() + " not found"));
        BlockStmt body = method.getBody().orElseThrow(() ->
                new CodeModificationException("Method " + change.method() + " has no body"));
        String anchor = Snippets.normalized(Snippets.parseStatement(change.anchorStatement()));
        Statement inserted = Snippets.parseStatement(change.statement());

        Statement target = body.findAll(Statement.class).stream()
                .filter(statement -> statement != body && Snippets.normalized(statement).equals(anchor))
                .findFirst()
                .orElseThrow(() -> new CodeModificationException(
                        "Anchor statement '" + change.anchorStatement() + "' not found in " + change.method()));
        if (!(target.getParentNode().orElseThrow() instanceof BlockStmt block)) {
            throw new CodeModificationException("Anchor statement '" + change.anchorStatement() + "' in "
                    + change.method() + " is not inside a block; cannot insert before it");
        }

        NodeList<Statement> statements = block.getStatements();
        int position = 0;
        while (statements.get(position) != target) { // by identity: NodeList.indexOf compares structurally
            position++;
        }
        if (position > 0 && Snippets.normalized(statements.get(position - 1)).equals(Snippets.normalized(inserted))) {
            return List.of();
        }

        int at = source.begin(target);
        String indentation = source.indentationAt(at);
        String separator = source.startsLine(at) ? source.lineSeparator() + indentation : " ";
        return List.of(new SourceText.Edit(at, at,
                source.indentContinuation(inserted.toString(), indentation) + separator));
    }
}
