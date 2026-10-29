package com.project.migration.infrastructure.ast;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Parsed sources of the target project, indexed by fully-qualified type name (nested types included).
 * Loaded lazily and invalidated after {@link JavaParserCodeModifier} rewrites files.
 * <p>
 * The target project's dependencies (Appium, Selenium, TestNG) are not on the engine classpath: symbol
 * resolution covers project types and the JDK only, and callers fall back to name-based lookup for the rest.
 */
final class ProjectSourceIndex {

    private static final Logger LOG = LoggerFactory.getLogger(ProjectSourceIndex.class);

    static final ParserConfiguration.LanguageLevel LANGUAGE_LEVEL = ParserConfiguration.LanguageLevel.JAVA_21;

    private final Path projectRoot;
    private final List<Path> configuredRoots;
    private final SourceRootLocator rootLocator = new SourceRootLocator();

    private List<Path> sourceRoots;
    private JavaParser parser;
    private final Map<String, TypeDeclaration<?>> typesByFqcn = new TreeMap<>();
    private final Map<String, Path> filesByFqcn = new HashMap<>();

    ProjectSourceIndex(Path projectRoot, List<Path> configuredRoots) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.configuredRoots = configuredRoots.stream()
                .map(root -> this.projectRoot.resolve(root).normalize())
                .toList();
    }

    synchronized Optional<TypeDeclaration<?>> type(String fqcn) {
        ensureLoaded();
        return Optional.ofNullable(typesByFqcn.get(fqcn));
    }

    synchronized Optional<Path> fileOf(String fqcn) {
        ensureLoaded();
        return Optional.ofNullable(filesByFqcn.get(fqcn));
    }

    /** All types, ordered by fully-qualified name. */
    synchronized Collection<TypeDeclaration<?>> types() {
        ensureLoaded();
        return List.copyOf(typesByFqcn.values());
    }

    synchronized List<Path> sourceRoots() {
        ensureLoaded();
        return sourceRoots;
    }

    /**
     * Drops the parsed state after files were rewritten; the next query re-parses the project. Symbol-solver
     * caches are tied to the parser, so a partial refresh would resolve against stale declarations.
     */
    synchronized void invalidate() {
        parser = null;
        typesByFqcn.clear();
        filesByFqcn.clear();
    }

    /**
     * Fully-qualified name of a type as written in {@code unit} ({@code LoginPage}, {@code pages.LoginPage},
     * {@code Outer.Inner}), if it is a project type.
     */
    synchronized Optional<String> resolveTypeName(CompilationUnit unit, String writtenName) {
        ensureLoaded();
        String name = stripGenerics(writtenName);
        if (typesByFqcn.containsKey(name)) {
            return Optional.of(name);
        }
        String first = name.contains(".") ? name.substring(0, name.indexOf('.')) : name;
        String rest = name.contains(".") ? name.substring(name.indexOf('.')) : "";
        for (ImportDeclaration imp : unit.getImports()) {
            if (imp.isStatic()) {
                continue;
            }
            String imported = imp.getNameAsString();
            if (!imp.isAsterisk() && imported.endsWith("." + first) && typesByFqcn.containsKey(imported + rest)) {
                return Optional.of(imported + rest);
            }
        }
        List<String> candidates = new ArrayList<>();
        unit.getPackageDeclaration().ifPresent(pkg -> candidates.add(pkg.getNameAsString() + "." + name));
        unit.getTypes().forEach(top -> candidates.add(top.getFullyQualifiedName().orElse("") + "." + name));
        unit.getImports().stream()
                .filter(imp -> imp.isAsterisk() && !imp.isStatic())
                .forEach(imp -> candidates.add(imp.getNameAsString() + "." + name));
        return candidates.stream().filter(typesByFqcn::containsKey).findFirst();
    }

    /** Project superclass of a class, if it extends one. */
    synchronized Optional<String> superclassOf(String fqcn) {
        return type(fqcn)
                .filter(TypeDeclaration::isClassOrInterfaceDeclaration)
                .map(TypeDeclaration::asClassOrInterfaceDeclaration)
                .filter(decl -> !decl.isInterface())
                .flatMap(decl -> decl.getExtendedTypes().getFirst())
                .map(ClassOrInterfaceType::getNameWithScope)
                .flatMap(written -> resolveTypeName(unitOf(fqcn), written));
    }

    /** The class itself followed by its project superclasses, nearest first. */
    synchronized List<String> hierarchyOf(String fqcn) {
        List<String> hierarchy = new ArrayList<>();
        Optional<String> current = type(fqcn).isPresent() ? Optional.of(fqcn) : Optional.empty();
        while (current.isPresent() && !hierarchy.contains(current.get())) {
            hierarchy.add(current.get());
            current = superclassOf(current.get());
        }
        return hierarchy;
    }

    synchronized CompilationUnit unitOf(String fqcn) {
        return type(fqcn).flatMap(TypeDeclaration::findCompilationUnit)
                .orElseThrow(() -> new IllegalArgumentException("Unknown type " + fqcn));
    }

    static String stripGenerics(String typeName) {
        int generic = typeName.indexOf('<');
        String raw = generic < 0 ? typeName : typeName.substring(0, generic);
        return raw.replace("[]", "").replace("...", "").trim();
    }

    private void ensureLoaded() {
        if (parser != null) {
            return;
        }
        sourceRoots = configuredRoots.isEmpty() ? rootLocator.locate(projectRoot) : configuredRoots;
        parser = newParser(sourceRoots);
        for (Path root : sourceRoots) {
            javaFilesUnder(root).forEach(this::addFile);
        }
        LOG.debug("Indexed {} types from {} source roots", typesByFqcn.size(), sourceRoots.size());
    }

    private static JavaParser newParser(List<Path> roots) {
        CombinedTypeSolver typeSolver = new CombinedTypeSolver(new ReflectionTypeSolver(true));
        ParserConfiguration solverConfiguration = new ParserConfiguration().setLanguageLevel(LANGUAGE_LEVEL);
        roots.stream().filter(Files::isDirectory)
                .forEach(root -> typeSolver.add(new JavaParserTypeSolver(root, solverConfiguration)));
        return new JavaParser(new ParserConfiguration()
                .setLanguageLevel(LANGUAGE_LEVEL)
                .setSymbolResolver(new JavaSymbolSolver(typeSolver)));
    }

    private static List<Path> javaFilesUnder(Path root) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(file -> file.toString().endsWith(".java") && Files.isRegularFile(file))
                    .map(file -> file.toAbsolutePath().normalize())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list sources under " + root, e);
        }
    }

    private void addFile(Path file) {
        ParseResult<CompilationUnit> result;
        try {
            result = parser.parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            LOG.warn("Skipping unparsable source {}: {}", file, result.getProblems());
            return;
        }
        CompilationUnit unit = result.getResult().get();
        unit.setStorage(file, StandardCharsets.UTF_8);
        for (TypeDeclaration<?> type : unit.findAll(TypeDeclaration.class)) {
            if (!type.isTopLevelType() && !type.isNestedType()) {
                continue; // local and anonymous classes are not addressable by name
            }
            type.getFullyQualifiedName().ifPresent(fqcn -> {
                typesByFqcn.put(fqcn, type);
                filesByFqcn.put(fqcn, file);
            });
        }
    }
}
