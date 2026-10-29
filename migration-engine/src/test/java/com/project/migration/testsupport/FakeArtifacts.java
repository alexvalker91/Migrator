package com.project.migration.testsupport;

import com.project.migration.domain.model.media.ImageAttachment;
import com.project.migration.domain.port.ArtifactStorePort;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Artifact files from a map instead of the file system.
 */
public final class FakeArtifacts implements ArtifactStorePort {

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

    public final java.util.List<Path> copied = new java.util.ArrayList<>();

    @Override
    public Optional<Path> copyInto(Path file, Path directory) {
        if (!texts.containsKey(file) && !images.containsKey(file)) {
            return Optional.empty();
        }
        Path target = directory.resolve(file.getFileName().toString());
        copied.add(target);
        if (texts.containsKey(file)) {
            texts.put(target, texts.get(file));
        }
        if (images.containsKey(file)) {
            images.put(target, images.get(file));
        }
        return Optional.of(target);
    }

    @Override
    public Optional<Path> writeText(Path file, String content) {
        texts.put(file, content);
        return Optional.of(file);
    }
}
