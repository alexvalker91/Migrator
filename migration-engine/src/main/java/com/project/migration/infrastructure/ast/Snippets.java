package com.project.migration.infrastructure.ast;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.printer.configuration.DefaultConfigurationOption;
import com.github.javaparser.printer.configuration.DefaultPrinterConfiguration;
import com.github.javaparser.printer.configuration.PrinterConfiguration;
import com.project.migration.domain.exception.CodeModificationException;

import java.util.Optional;
import java.util.function.Function;

/**
 * Parsing of LLM-produced code fragments, AST comparison and import edits for the change appliers.
 */
final class Snippets {

    private static final PrinterConfiguration WITHOUT_COMMENTS = new DefaultPrinterConfiguration()
            .removeOption(new DefaultConfigurationOption(DefaultPrinterConfiguration.ConfigOption.PRINT_COMMENTS));

    private Snippets() {
    }

    static JavaParser parser() {
        return new JavaParser(new ParserConfiguration().setLanguageLevel(ProjectSourceIndex.LANGUAGE_LEVEL));
    }

    /** A single statement; a missing trailing {@code ;} is added. */
    static Statement parseStatement(String source) {
        String trimmed = source.strip();
        String statement = trimmed.endsWith(";") || trimmed.endsWith("}") ? trimmed : trimmed + ";";
        return parse(statement, parser -> parser.parseStatement(statement), "statement");
    }

    /** A method body; braces are added when the fragment has none. */
    static BlockStmt parseBlock(String source) {
        String trimmed = source.strip();
        String block = trimmed.startsWith("{") ? trimmed : "{\n" + trimmed + "\n}";
        return parse(block, parser -> parser.parseBlock(block), "method body");
    }

    /** Structural form for comparisons: pretty-printed without comments, whitespace collapsed. */
    static String normalized(Node node) {
        return node.toString(WITHOUT_COMMENTS).replaceAll("\\s+", " ").strip();
    }

    /**
     * Edit adding {@code import [static] name;} after the last import (or the package declaration), unless the
     * import, a covering wildcard, the same package or {@code java.lang} make it redundant.
     */
    static Optional<SourceText.Edit> importEdit(CompilationUnit unit, SourceText source, String name, boolean isStatic) {
        String parent = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : "";
        boolean implicit = !isStatic && (parent.equals("java.lang")
                || unit.getPackageDeclaration().map(pkg -> pkg.getNameAsString().equals(parent)).orElse(parent.isEmpty()));
        boolean covered = unit.getImports().stream()
                .filter(existing -> existing.isStatic() == isStatic)
                .anyMatch(existing -> existing.isAsterisk()
                        ? existing.getNameAsString().equals(parent)
                        : existing.getNameAsString().equals(name));
        if (implicit || covered) {
            return Optional.empty();
        }
        String declaration = "import " + (isStatic ? "static " : "") + name + ";";
        String separator = source.lineSeparator();
        if (unit.getImports().isNonEmpty()) {
            ImportDeclaration last = unit.getImports().getLast().orElseThrow();
            int at = source.end(last);
            return Optional.of(new SourceText.Edit(at, at, separator + declaration));
        }
        if (unit.getPackageDeclaration().isPresent()) {
            int at = source.end(unit.getPackageDeclaration().get());
            return Optional.of(new SourceText.Edit(at, at, separator + separator + declaration));
        }
        return Optional.of(new SourceText.Edit(0, 0, declaration + separator + separator));
    }

    private static <T extends Node> T parse(String source, Function<JavaParser, ParseResult<T>> parse, String kind) {
        ParseResult<T> result = parse.apply(parser());
        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            throw new CodeModificationException("Generated " + kind + " does not parse: " + result.getProblems()
                    + "\n" + source);
        }
        return result.getResult().get();
    }
}
