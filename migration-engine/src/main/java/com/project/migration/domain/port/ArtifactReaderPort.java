package com.project.migration.domain.port;

import com.project.migration.domain.model.media.ImageAttachment;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Reads files the test run left behind (failure screenshot, screen XML). A file that is gone or unreadable is
 * simply absent: missing evidence weakens a diagnosis, it does not stop the migration.
 */
public interface ArtifactReaderPort {

    Optional<ImageAttachment> readImage(Path file);

    Optional<String> readText(Path file);
}
