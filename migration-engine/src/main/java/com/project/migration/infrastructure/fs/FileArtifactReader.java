package com.project.migration.infrastructure.fs;

import com.project.migration.domain.model.media.ImageAttachment;
import com.project.migration.domain.port.ArtifactReaderPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * {@link ArtifactReaderPort} on the file system.
 */
public final class FileArtifactReader implements ArtifactReaderPort {

    private static final Logger LOG = LoggerFactory.getLogger(FileArtifactReader.class);

    @Override
    public Optional<ImageAttachment> readImage(Path file) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            return bytes.length == 0 ? Optional.empty() : Optional.of(ImageAttachment.png(bytes));
        } catch (IOException e) {
            LOG.warn("Cannot read image {}: {}", file, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public Optional<String> readText(Path file) {
        try {
            return Optional.of(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            LOG.warn("Cannot read {}: {}", file, e.getMessage());
            return Optional.empty();
        }
    }
}
