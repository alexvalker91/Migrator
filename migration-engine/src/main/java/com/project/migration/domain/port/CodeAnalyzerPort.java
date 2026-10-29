package com.project.migration.domain.port;

import com.project.migration.domain.model.analysis.ComplexityMarker;
import com.project.migration.domain.model.code.BodyInspection;
import com.project.migration.domain.model.code.FieldRef;
import com.project.migration.domain.model.code.LocatorField;
import com.project.migration.domain.model.code.MethodRef;
import com.project.migration.domain.model.code.TestMethodRef;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Read-only AST view of the target project (JavaParser + symbol resolution in the adapter).
 * Unknown classes or methods yield empty results, not exceptions.
 */
public interface CodeAnalyzerPort {

    /**
     * Page Object classes of the project: subclasses of the base page class with at least one locator field.
     * Classes of global components are not excluded here; the caller decides.
     */
    List<String> listScreenClasses();

    /** {@code @Test} methods of a class, in source order. */
    List<TestMethodRef> listTestMethods(String testClassFqcn);

    /** Every {@code @Test} method of the project (project-wide progress). */
    List<TestMethodRef> listAllTestMethods();

    /** Page Object / step methods reachable from the test, in first-call order, without duplicates. */
    List<MethodRef> resolveCallTree(TestMethodRef test);

    /** Locator fields ({@code @AndroidFindBy}) a method uses directly. */
    List<LocatorField> findLocatorFields(MethodRef method);

    /** Every locator field of the class and its project superclasses, in declaration order. */
    List<LocatorField> listLocatorFields(String classFqcn);

    Set<ComplexityMarker> detectComplexityMarkers(MethodRef method);

    /** Markers in the body of the test method itself (inline locators, platform calls written in the test). */
    Set<ComplexityMarker> detectComplexityMarkers(TestMethodRef test);

    /**
     * Top-level statements of {@code method}'s body that read {@code field}, in source order, in the normalised
     * form accepted as an anchor by {@code InsertCallBefore}.
     */
    List<String> statementsUsingField(MethodRef method, FieldRef field);

    /**
     * Checks a candidate body for {@code method} without writing anything: it must parse, contain no
     * Android-specific constructs and call only existing methods (framework helpers: the documented ones).
     * An unknown method yields an inspection with a syntax error.
     */
    BodyInspection inspectBody(MethodRef method, String newBody);

    Set<String> findTestGroups(TestMethodRef test);

    Optional<String> methodSource(MethodRef method);

    /**
     * Sources of the setup methods ({@code @BeforeMethod}, {@code @BeforeClass}, ...) a test class runs, base
     * classes first. They show how the app is logged in before the test starts.
     */
    List<String> setupMethodSources(String testClassFqcn);

    /** Source of the {@code @Test} method as written, with its annotations. */
    Optional<String> testSource(TestMethodRef test);

    Optional<Path> sourceFileOf(String classFqcn);
}
