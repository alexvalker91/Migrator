package com.project.migration.testsupport;

import com.project.migration.domain.model.media.ImageAttachment;
import com.project.migration.domain.port.ArtifactReaderPort;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Artifact files from a map instead of the file system.
 */
public final class FakeArtifacts implements ArtifactReaderPort {

    public final Map<Path, String> texts = new HashMap<>();
    public final Map<Path, byte[]> images = new HashMap<>();

    @Override
    public Optional<ImageAttachment> readImage(Path file) {
        return Optional.ofNullable(images.get(file)).map(ImageAttachment::png);
    }

    @Override
    public Optional<String> readText(Path file) {
        return Optional.ofNullable(texts.get(file));
    }
}
