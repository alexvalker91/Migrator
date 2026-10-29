package com.project.migration.domain.port;

import com.project.migration.domain.model.media.ImageAttachment;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Reads the files a test run left behind (failure screenshot, screen XML) and keeps evidence for the report.
 * A file that is gone or unreadable is simply absent: missing evidence weakens a diagnosis, it does not stop the
 * migration.
 */
public interface ArtifactStorePort {

    Optional<ImageAttachment> readImage(Path file);

    Optional<String> readText(Path file);

    /**
     * Copies {@code file} into {@code directory} (created if needed) and returns the copy; empty if it could not be
     * copied. Build output is wiped by the next build, the copy is not.
     */
    Optional<Path> copyInto(Path file, Path directory);

    /** Writes {@code content} to {@code file} (parent directories created), returns it; empty on failure. */
    Optional<Path> writeText(Path file, String content);
}
