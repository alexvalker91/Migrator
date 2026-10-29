package com.project.migration.infrastructure.ast;

import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.project.migration.domain.exception.CodeModificationException;
import com.project.migration.domain.model.code.AddImport;
import com.project.migration.domain.model.code.AddIosLocator;
import com.project.migration.domain.model.code.AddTestGroups;
import com.project.migration.domain.model.code.CodeChange;
import com.project.migration.domain.model.code.InsertCallBefore;
import com.project.migration.domain.model.code.ModificationResult;
import com.project.migration.domain.model.code.ReplaceMethodBody;
import com.project.migration.domain.port.CodeModifierPort;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link CodeModifierPort} built on AST-located text edits: JavaParser finds the target nodes and their exact
 * positions, the change is spliced into the original text at those positions. Untouched code stays
 * byte-for-byte identical (JavaParser's lexical-preserving printer re-indents neighbouring code and joins
 * annotations onto one line, which would produce noisy diffs in shared Page Objects).
 * <p>
 * Changes are applied one by one in memory, each on a fresh parse of the current content, and every result
 * must parse again; files are written only when the whole batch succeeded (all or nothing).
 */
final class JavaParserCodeModifier implements CodeModifierPort {

    private final ProjectSourceIndex index;
    private final AtomicFileWriter writer = new AtomicFileWriter();
    private final AddIosLocatorApplier addIosLocator = new AddIosLocatorApplier();
    private final InsertCallBeforeApplier insertCallBefore = new InsertCallBeforeApplier();
    private final ReplaceMethodBodyApplier replaceMethodBody = new ReplaceMethodBodyApplier();
    private final AddTestGroupsApplier addTestGroups = new AddTestGroupsApplier();

    JavaParserCodeModifier(ProjectSourceIndex index) {
        this.index = index;
    }

    @Override
    public ModificationResult apply(List<CodeChange> changes) {
        Map<Path, String> originals = new LinkedHashMap<>();
        Map<Path, String> contents = new LinkedHashMap<>();
        int applied = 0;
        for (CodeChange change : changes) {
            Path file = index.fileOf(change.targetClassFqcn()).orElseThrow(() ->
                    new CodeModificationException("Class " + change.targetClassFqcn() + " not found in project sources"));
            String current = contents.computeIfAbsent(file, path -> originals.computeIfAbsent(path, JavaParserCodeModifier::read));
            String updated = applyOne(file, current, change);
            if (!updated.equals(current)) {
                contents.put(file, updated);
                applied++;
            }
        }

        Map<Path, String> changed = new LinkedHashMap<>();
        contents.forEach((file, content) -> {
            if (!content.equals(originals.get(file))) {
                changed.put(file, content);
            }
        });
        if (!changed.isEmpty()) {
            writer.writeAll(changed, originals);
            index.invalidate();
        }
        return new ModificationResult(List.copyOf(changed.keySet()), applied);
    }

    private String applyOne(Path file, String content, CodeChange change) {
        CompilationUnit unit = parse(file, content, "Cannot parse");
        TypeDeclaration<?> type = unit.findAll(TypeDeclaration.class).stream()
                .filter(candidate -> candidate.getFullyQualifiedName().filter(change.targetClassFqcn()::equals).isPresent())
                .<TypeDeclaration<?>>map(candidate -> candidate)
                .findFirst()
                .orElseThrow(() -> new CodeModificationException(
                        "Class " + change.targetClassFqcn() + " not found in " + file));
        SourceText source = new SourceText(content);
        List<SourceText.Edit> edits;
        try {
            edits = switch (change) {
                case AddIosLocator c -> addIosLocator.edits(unit, source, type, c);
                case InsertCallBefore c -> insertCallBefore.edits(unit, source, type, c);
                case ReplaceMethodBody c -> replaceMethodBody.edits(unit, source, type, c);
                case AddTestGroups c -> addTestGroups.edits(unit, source, type, c);
                case AddImport c -> Snippets.importEdit(unit, source, c.importName(), c.isStatic()).stream().toList();
            };
        } catch (CodeModificationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new CodeModificationException("Cannot apply " + change + " to " + file + ": " + e.getMessage(), e);
        }
        if (edits.isEmpty()) {
            return content;
        }
        String updated = source.apply(edits);
        parse(file, updated, "Applying " + change + " produced a file that does not parse");
        return updated;
    }

    private static CompilationUnit parse(Path file, String content, String failure) {
        ParseResult<CompilationUnit> result = Snippets.parser().parse(content);
        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            throw new CodeModificationException(failure + " (" + file + "): " + result.getProblems());
        }
        return result.getResult().get();
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
    }
}
