package com.project.migration.infrastructure.ast;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.project.migration.domain.model.analysis.ComplexityMarker;
import com.project.migration.domain.model.code.BodyInspection;
import com.project.migration.domain.model.code.FieldRef;
import com.project.migration.domain.model.code.FrameworkContract;
import com.project.migration.domain.model.code.LocatorField;
import com.project.migration.domain.model.code.MethodRef;
import com.project.migration.domain.model.code.TestMethodRef;
import com.project.migration.domain.model.config.EngineConfig;
import com.project.migration.domain.port.CodeAnalyzerPort;
import com.project.migration.domain.port.CodeModifierPort;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * {@link CodeAnalyzerPort} over JavaParser (+ symbol solver). Shares its {@link ProjectSourceIndex} with the
 * {@link #modifier() modifier}, which invalidates it after every write so analysis always sees the files on disk.
 */
public final class JavaParserAstAdapter implements CodeAnalyzerPort {

    private final ProjectSourceIndex index;
    private final TestMethodScanner tests;
    private final CallTreeResolver callTrees;
    private final LocatorFieldFinder locators;
    private final ComplexityMarkerDetector markers;
    private final SetupMethodFinder setups;
    private final BodyInspector bodies;
    private final JavaParserCodeModifier modifier;

    public JavaParserAstAdapter(EngineConfig config) {
        this(config.projectRoot(), config.sourceRoots(), config.framework());
    }

    /**
     * @param sourceRoots empty = every {@code src/main/java} and {@code src/test/java} under {@code projectRoot}
     */
    public JavaParserAstAdapter(Path projectRoot, List<Path> sourceRoots, FrameworkContract framework) {
        this.index = new ProjectSourceIndex(projectRoot, sourceRoots);
        this.tests = new TestMethodScanner(index);
        this.callTrees = new CallTreeResolver(index, new CallTargetResolver(index), framework);
        this.locators = new LocatorFieldFinder(index);
        this.markers = new ComplexityMarkerDetector(index, framework);
        this.setups = new SetupMethodFinder(index);
        this.bodies = new BodyInspector(index, markers, framework);
        this.modifier = new JavaParserCodeModifier(index);
    }

    /** The {@link CodeModifierPort} working on the same sources. */
    public CodeModifierPort modifier() {
        return modifier;
    }

    @Override
    public List<TestMethodRef> listTestMethods(String testClassFqcn) {
        return tests.testMethods(testClassFqcn);
    }

    @Override
    public List<TestMethodRef> listAllTestMethods() {
        return tests.allTestMethods();
    }

    @Override
    public List<MethodRef> resolveCallTree(TestMethodRef test) {
        Optional<MethodDeclaration> declaration = tests.declaration(test);
        return declaration.map(callTrees::resolve).orElse(List.of());
    }

    @Override
    public List<LocatorField> findLocatorFields(MethodRef method) {
        return locators.find(method);
    }

    @Override
    public Set<ComplexityMarker> detectComplexityMarkers(MethodRef method) {
        return markers.detect(method);
    }

    @Override
    public Set<ComplexityMarker> detectComplexityMarkers(TestMethodRef test) {
        return tests.declaration(test)
                .map(declaration -> markers.detect(SourceLookup.refOf(test.classFqcn(), declaration)))
                .orElse(Set.of());
    }

    @Override
    public List<String> statementsUsingField(MethodRef method, FieldRef field) {
        return locators.statementsUsing(method, field);
    }

    @Override
    public BodyInspection inspectBody(MethodRef method, String newBody) {
        return bodies.inspect(method, newBody);
    }

    @Override
    public Set<String> findTestGroups(TestMethodRef test) {
        return tests.groups(test);
    }

    /** Method source exactly as written (original formatting and comments inside the method). */
    @Override
    public Optional<String> methodSource(MethodRef method) {
        return index.type(method.classFqcn())
                .flatMap(type -> SourceLookup.findMethod(type, method))
                .flatMap(declaration -> declaration.getTokenRange().map(Object::toString));
    }

    @Override
    public List<String> setupMethodSources(String testClassFqcn) {
        return setups.sources(testClassFqcn);
    }

    @Override
    public Optional<String> testSource(TestMethodRef test) {
        return tests.declaration(test).flatMap(declaration -> declaration.getTokenRange().map(Object::toString));
    }

    @Override
    public Optional<Path> sourceFileOf(String classFqcn) {
        return index.fileOf(classFqcn);
    }

    /** Source roots in use (configured or discovered). */
    public List<Path> sourceRoots() {
        return index.sourceRoots();
    }
}
