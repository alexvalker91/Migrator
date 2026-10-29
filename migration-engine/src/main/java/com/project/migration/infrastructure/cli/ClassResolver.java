package com.project.migration.infrastructure.cli;

import com.project.migration.domain.model.code.CodeNames;
import com.project.migration.domain.model.code.TestMethodRef;
import com.project.migration.domain.port.CodeAnalyzerPort;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Finds the test class a name on the command line means, among the classes that have {@code @Test} methods.
 */
final class ClassResolver {

    private final Set<String> testClasses = new TreeSet<>();

    ClassResolver(CodeAnalyzerPort analyzer) {
        analyzer.listAllTestMethods().stream().map(TestMethodRef::classFqcn).forEach(testClasses::add);
    }

    String resolve(String name) {
        String cleaned = name.endsWith(".java") ? name.substring(0, name.length() - ".java".length()) : name;
        if (cleaned.contains(".")) {
            if (testClasses.contains(cleaned)) {
                return cleaned;
            }
            throw new UsageException("'" + name + "' is not a test class of the project");
        }
        List<String> matches = testClasses.stream().filter(fqcn -> CodeNames.simpleName(fqcn).equals(cleaned)).toList();
        if (matches.isEmpty()) {
            throw new UsageException("no test class named '" + name + "' in the project (" + testClasses.size() + " test classes found)");
        }
        if (matches.size() > 1) {
            throw new UsageException("'" + name + "' is ambiguous, use one of: " + String.join(", ", matches));
        }
        return matches.getFirst();
    }
}
