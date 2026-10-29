package com.project.migration.infrastructure.ast;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Finds the Java source roots of a (possibly multi-module) Maven project: every {@code src/main/java} and
 * {@code src/test/java}, skipping build output, VCS/IDE folders and the engine's own data.
 */
final class SourceRootLocator {

    private static final Set<String> SKIPPED_DIRECTORIES = Set.of(
            "target", "build", "out", "node_modules", ".git", ".idea", ".mvn", ".migration", "migration_reports");
    private static final List<Path> ROOT_SUFFIXES = List.of(Path.of("src", "main", "java"), Path.of("src", "test", "java"));

    List<Path> locate(Path projectRoot) {
        List<Path> roots = new ArrayList<>();
        try {
            Files.walkFileTree(projectRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(projectRoot) && SKIPPED_DIRECTORIES.contains(dir.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    Path relative = projectRoot.relativize(dir);
                    if (ROOT_SUFFIXES.stream().anyMatch(relative::endsWith)) {
                        roots.add(dir);
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot scan source roots under " + projectRoot, e);
        }
        roots.sort(null);
        return List.copyOf(roots);
    }
}
