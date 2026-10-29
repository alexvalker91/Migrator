package com.project.migration.infrastructure.ast;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.project.migration.domain.exception.CodeModificationException;
import com.project.migration.domain.model.code.AddIosLocator;

import java.util.ArrayList;
import java.util.List;

/**
 * Puts {@code @iOSXCUITFindBy(<strategy> = "<value>")} directly above the field's Android locator (same
 * indentation), replacing any iOS locator already there, and imports the annotation.
 */
final class AddIosLocatorApplier implements ChangeApplier<AddIosLocator> {

    static final String IOS_FIND_BY_FQCN = "io.appium.java_client.pagefactory.iOSXCUITFindBy";

    @Override
    public List<SourceText.Edit> edits(CompilationUnit unit, SourceText source, TypeDeclaration<?> type,
                                       AddIosLocator change) {
        FieldDeclaration field = SourceLookup.findField(type, change.field().fieldName()).orElseThrow(() ->
                new CodeModificationException("Field " + change.field() + " not found"));
        if (field.getVariables().size() > 1) {
            throw new CodeModificationException("Field declaration of " + change.field()
                    + " declares several variables; an iOS locator would apply to all of them");
        }
        AnnotationExpr android = SourceLookup.annotation(field, LocatorFieldFinder.ANDROID_ANNOTATIONS).orElseThrow(() ->
                new CodeModificationException("Field " + change.field() + " has no @AndroidFindBy locator"));

        String desired = "@iOSXCUITFindBy(" + change.strategy().annotationAttribute() + " = \""
                + new StringLiteralExpr().setString(change.value()).getValue() + "\")";
        List<AnnotationExpr> existing = SourceLookup.annotations(field, LocatorFieldFinder.IOS_ANNOTATIONS);
        List<SourceText.Edit> edits = new ArrayList<>();
        Snippets.importEdit(unit, source, IOS_FIND_BY_FQCN, false).ifPresent(edits::add);
        boolean alreadyInPlace = existing.size() == 1 && Snippets.normalized(existing.getFirst())
                .endsWith(desired.substring(1));
        if (alreadyInPlace) {
            return edits;
        }

        existing.forEach(annotation -> edits.add(source.removal(annotation)));
        int at = source.begin(android);
        String separator = source.startsLine(at) ? source.lineSeparator() + source.indentationAt(at) : " ";
        edits.add(new SourceText.Edit(at, at, desired + separator));
        return edits;
    }
}
