package com.project.migration.infrastructure.maven;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Finds what the target project's failure listener saved for a test:
 * {@code target/screenshots/<method>_<timestamp>.png} and {@code target/page-source/<method>_<timestamp>.xml}.
 * Only files written since the run started count, so old failures are never attributed to this one.
 */
final class FailureArtifactFinder {

    static final String SCREENSHOT_DIR = "screenshots";
    static final String PAGE_SOURCE_DIR = "page-source";
    private static final int MAX_DEPTH = 5;

    private final Path projectRoot;

    FailureArtifactFinder(Path projectRoot) {
        this.projectRoot = projectRoot;
    }

    Optional<Path> screenshot(String methodName, Instant since) {
        return newest(SCREENSHOT_DIR, methodName, ".png", since);
    }

    Optional<Path> pageSource(String methodName, Instant since) {
        return newest(PAGE_SOURCE_DIR, methodName, ".xml", since);
    }

    private Optional<Path> newest(String directory, String methodName, String extension, Instant since) {
        try (Stream<Path> files = Files.walk(projectRoot, MAX_DEPTH)) {
            return files
                    .filter(file -> file.getParent() != null && file.getParent().getFileName() != null
                            && file.getParent().getFileName().toString().equals(directory)
                            && file.getParent().getParent() != null
                            && file.getParent().getParent().getFileName().toString().equals("target"))
                    .filter(file -> file.getFileName().toString().startsWith(methodName + "_")
                            && file.getFileName().toString().endsWith(extension))
                    .filter(file -> modifiedSince(file, since))
                    .max(Comparator.comparing(FailureArtifactFinder::modified));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static boolean modifiedSince(Path file, Instant since) {
        return !modified(file).isBefore(since);
    }

    private static Instant modified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toInstant();
        } catch (IOException e) {
            return Instant.EPOCH;
        }
    }
}
