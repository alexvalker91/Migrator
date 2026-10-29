package com.project.migration.infrastructure.snapshot;

import com.project.migration.domain.exception.CacheCorruptedException;
import com.project.migration.domain.model.Platform;
import com.project.migration.domain.model.media.ImageAttachment;
import com.project.migration.domain.model.screen.ScreenId;
import com.project.migration.domain.model.screen.ScreenState;
import com.project.migration.domain.model.screen.ScreenStateKey;
import com.project.migration.domain.model.screen.StateSnapshot;
import com.project.migration.domain.port.SnapshotRepositoryPort;
import com.project.migration.infrastructure.fs.AtomicFiles;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * {@link SnapshotRepositoryPort} on {@code .migration/snapshots/<ScreenName>/state_<STATE>[_<variant>]_<platform>_clean.xml}
 * (+ {@code .png}). The screenshot is written before the XML, so an existing XML always means a complete
 * snapshot; a crash in between leaves only an orphan PNG that the next save overwrites. {@code capturedAt} is
 * the XML file's modification time: no sidecar files and no headers that would cost prompt tokens.
 */
public final class FileSnapshotRepository implements SnapshotRepositoryPort {

    static final String SNAPSHOTS_DIR = "snapshots";
    private static final String STEM_PREFIX = "state_";
    private static final String CLEAN_XML_SUFFIX = "_clean.xml";

    /** Longest names first, so {@code BOTTOM_SHEET_x} is not read as a {@code BOTTOM} state. */
    private static final List<ScreenState> STATES_BY_NAME_LENGTH = Arrays.stream(ScreenState.values())
            .sorted(Comparator.comparingInt((ScreenState state) -> state.name().length()).reversed())
            .toList();

    private final Path root;

    public FileSnapshotRepository(Path migrationDir) {
        this.root = migrationDir.toAbsolutePath().normalize().resolve(SNAPSHOTS_DIR);
    }

    @Override
    public boolean exists(ScreenStateKey key, Platform platform) {
        return Files.isRegularFile(xmlFile(key, platform));
    }

    @Override
    public Optional<StateSnapshot> find(ScreenStateKey key, Platform platform) {
        Path xml = xmlFile(key, platform);
        if (!Files.isRegularFile(xml)) {
            return Optional.empty();
        }
        try {
            String cleanXml = decode(xml, Files.readAllBytes(xml));
            if (cleanXml.isBlank()) {
                throw new CacheCorruptedException("Snapshot " + xml + " is empty");
            }
            Path png = pngFile(key, platform);
            Optional<ImageAttachment> screenshot = Files.isRegularFile(png)
                    ? Optional.of(readScreenshot(png))
                    : Optional.empty();
            return Optional.of(new StateSnapshot(key, platform, cleanXml, screenshot,
                    Files.getLastModifiedTime(xml).toInstant()));
        } catch (IOException e) {
            throw new CacheCorruptedException("Cannot read snapshot " + xml, e);
        }
    }

    @Override
    public void save(StateSnapshot snapshot) {
        Path xml = xmlFile(snapshot.key(), snapshot.platform());
        try {
            if (snapshot.screenshot().isPresent()) {
                ImageAttachment screenshot = snapshot.screenshot().get();
                if (!ImageAttachment.PNG.equals(screenshot.mimeType())) {
                    throw new IllegalArgumentException("Snapshots store PNG screenshots, got " + screenshot.mimeType());
                }
                AtomicFiles.write(pngFile(snapshot.key(), snapshot.platform()), screenshot.data());
            }
            AtomicFiles.writeString(xml, snapshot.cleanXml());
            Files.setLastModifiedTime(xml, FileTime.from(snapshot.capturedAt()));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write snapshot " + xml, e);
        }
    }

    @Override
    public Set<ScreenStateKey> findKeys(ScreenId screen, Platform platform) {
        Path dir = root.resolve(screen.value());
        if (!Files.isDirectory(dir)) {
            return Set.of();
        }
        String suffix = "_" + platform.fileToken() + CLEAN_XML_SUFFIX;
        try (Stream<Path> files = Files.list(dir)) {
            Set<ScreenStateKey> keys = new LinkedHashSet<>();
            files.map(file -> file.getFileName().toString())
                    .filter(name -> name.startsWith(STEM_PREFIX) && name.endsWith(suffix))
                    .sorted()
                    .forEach(name -> parseKey(screen, name.substring(STEM_PREFIX.length(), name.length() - suffix.length()))
                            .ifPresent(keys::add));
            return Set.copyOf(keys);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list snapshots of " + screen, e);
        }
    }

    /** {@code BOTTOM_SHEET_card_picker} → (BOTTOM_SHEET, card_picker); {@code DEFAULT} → (DEFAULT, default). */
    static Optional<ScreenStateKey> parseKey(ScreenId screen, String stateAndVariant) {
        for (ScreenState state : STATES_BY_NAME_LENGTH) {
            String name = state.name();
            if (stateAndVariant.equals(name)) {
                return Optional.of(ScreenStateKey.of(screen, state));
            }
            if (stateAndVariant.startsWith(name + "_") && stateAndVariant.length() > name.length() + 1) {
                return Optional.of(ScreenStateKey.of(screen, state, stateAndVariant.substring(name.length() + 1)));
            }
        }
        return Optional.empty();
    }

    private Path xmlFile(ScreenStateKey key, Platform platform) {
        return root.resolve(key.screen().value()).resolve(key.cleanXmlFileName(platform));
    }

    private Path pngFile(ScreenStateKey key, Platform platform) {
        return root.resolve(key.screen().value()).resolve(key.screenshotFileName(platform));
    }

    private static ImageAttachment readScreenshot(Path png) throws IOException {
        byte[] data = Files.readAllBytes(png);
        if (data.length == 0) {
            throw new CacheCorruptedException("Screenshot " + png + " is empty");
        }
        return ImageAttachment.png(data);
    }

    private static String decode(Path file, byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new CacheCorruptedException("Snapshot " + file + " is not valid UTF-8", e);
        }
    }
}
