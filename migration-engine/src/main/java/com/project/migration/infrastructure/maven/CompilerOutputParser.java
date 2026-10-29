package com.project.migration.infrastructure.maven;

import com.project.migration.domain.model.run.CompilationError;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts javac errors from Maven output ({@code [ERROR] /path/File.java:[12,5] message}).
 */
final class CompilerOutputParser {

    private static final Pattern ERROR = Pattern.compile(
            "^\\[ERROR]\\s+(?:COMPILATION ERROR\\s*:\\s*)?(.+?\\.java):\\[(\\d+)(?:,\\d+)?]\\s+(.+)$");

    private CompilerOutputParser() {
    }

    /** Distinct errors in order of appearance (Maven prints each of them twice). */
    static List<CompilationError> parse(String mavenOutput) {
        Set<String> seen = new LinkedHashSet<>();
        List<CompilationError> errors = new ArrayList<>();
        for (String line : mavenOutput.split("\\R")) {
            Matcher matcher = ERROR.matcher(line.strip());
            if (matcher.matches() && seen.add(matcher.group(1) + matcher.group(2) + matcher.group(3))) {
                errors.add(new CompilationError(Path.of(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                        matcher.group(3).strip()));
            }
        }
        return errors;
    }
}
