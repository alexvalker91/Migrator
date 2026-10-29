package com.project.migration.infrastructure.fs;

import com.project.migration.domain.model.media.ImageAttachment;
import com.project.migration.domain.port.ArtifactStorePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * {@link ArtifactStorePort} on the file system.
 */
public final class FileArtifactStore implements ArtifactStorePort {

    private static final Logger LOG = LoggerFactory.getLogger(FileArtifactStore.class);

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

    @Override
    public Optional<Path> copyInto(Path file, Path directory) {
        try {
            Files.createDirectories(directory);
            Path target = directory.resolve(file.getFileName().toString());
            Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
            return Optional.of(target);
        } catch (IOException e) {
            LOG.warn("Cannot copy {} to {}: {}", file, directory, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public Optional<Path> writeText(Path file, String content) {
        try {
            AtomicFiles.writeString(file, content);
            return Optional.of(file);
        } catch (IOException e) {
            LOG.warn("Cannot write {}: {}", file, e.getMessage());
            return Optional.empty();
        }
    }
}
