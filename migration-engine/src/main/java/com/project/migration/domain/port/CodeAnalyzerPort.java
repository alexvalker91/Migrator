package com.project.migration.domain.port;

import com.project.migration.domain.model.analysis.ComplexityMarker;
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

    /** {@code @Test} methods of a class, in source order. */
    List<TestMethodRef> listTestMethods(String testClassFqcn);

    /** Every {@code @Test} method of the project (project-wide progress). */
    List<TestMethodRef> listAllTestMethods();

    /** Page Object / step methods reachable from the test, in first-call order, without duplicates. */
    List<MethodRef> resolveCallTree(TestMethodRef test);

    /** Locator fields ({@code @AndroidFindBy}) a method uses directly. */
    List<LocatorField> findLocatorFields(MethodRef method);

    Set<ComplexityMarker> detectComplexityMarkers(MethodRef method);

    Set<String> findTestGroups(TestMethodRef test);

    Optional<String> methodSource(MethodRef method);

    Optional<Path> sourceFileOf(String classFqcn);
}
