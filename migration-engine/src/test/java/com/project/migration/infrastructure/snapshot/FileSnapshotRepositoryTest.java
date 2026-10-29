package com.project.migration.infrastructure.snapshot;

import com.project.migration.domain.exception.CacheCorruptedException;
import com.project.migration.domain.model.Platform;
import com.project.migration.domain.model.media.ImageAttachment;
import com.project.migration.domain.model.screen.ScreenId;
import com.project.migration.domain.model.screen.ScreenState;
import com.project.migration.domain.model.screen.ScreenStateKey;
import com.project.migration.domain.model.screen.StateSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileSnapshotRepositoryTest {

    private static final ScreenId CARDS = new ScreenId("CardsPage");
    private static final Instant CAPTURED = Instant.parse("2026-10-29T10:15:30Z");
    private static final ImageAttachment PNG = ImageAttachment.png(new byte[]{(byte) 0x89, 'P', 'N', 'G', 1, 2, 3});

    @TempDir
    Path migrationDir;

    @Test
    void savesAndReadsXmlScreenshotAndCaptureTimeUnderSpecFileNames() {
        FileSnapshotRepository repository = new FileSnapshotRepository(migrationDir);
        ScreenStateKey key = ScreenStateKey.of(CARDS, ScreenState.BOTTOM_SHEET, "card_picker");
        StateSnapshot snapshot = new StateSnapshot(key, Platform.IOS, "<hierarchy platform=\"ios\"/>\n",
                Optional.of(PNG), CAPTURED);

        repository.save(snapshot);

        Path dir = migrationDir.resolve("snapshots/CardsPage");
        assertThat(dir.resolve("state_BOTTOM_SHEET_card_picker_ios_clean.xml")).exists();
        assertThat(dir.resolve("state_BOTTOM_SHEET_card_picker_ios.png")).exists();
        assertThat(repository.exists(key, Platform.IOS)).isTrue();
        assertThat(repository.exists(key, Platform.ANDROID)).isFalse();
        assertThat(repository.find(key, Platform.IOS)).contains(snapshot);
    }

    @Test
    void snapshotWithoutScreenshotHasNoPng() {
        FileSnapshotRepository repository = new FileSnapshotRepository(migrationDir);
        ScreenStateKey key = ScreenStateKey.of(CARDS, ScreenState.DEFAULT);
        repository.save(new StateSnapshot(key, Platform.ANDROID, "<hierarchy/>", Optional.empty(), CAPTURED));

        assertThat(repository.find(key, Platform.ANDROID).orElseThrow().screenshot()).isEmpty();
        assertThat(migrationDir.resolve("snapshots/CardsPage/state_DEFAULT_android.png")).doesNotExist();
    }

    @Test
    void orphanScreenshotWithoutXmlIsNotASnapshot() throws IOException {
        Path dir = Files.createDirectories(migrationDir.resolve("snapshots/CardsPage"));
        Files.write(dir.resolve("state_LOADING_ios.png"), PNG.data());
        FileSnapshotRepository repository = new FileSnapshotRepository(migrationDir);

        assertThat(repository.exists(ScreenStateKey.of(CARDS, ScreenState.LOADING), Platform.IOS)).isFalse();
        assertThat(repository.find(ScreenStateKey.of(CARDS, ScreenState.LOADING), Platform.IOS)).isEmpty();
        assertThat(repository.findKeys(CARDS, Platform.IOS)).isEmpty();
    }

    @Test
    void findKeysParsesStatesWithUnderscoresAndVariantsPerPlatform() {
        FileSnapshotRepository repository = new FileSnapshotRepository(migrationDir);
        for (ScreenStateKey key : new ScreenStateKey[]{
                ScreenStateKey.of(CARDS, ScreenState.DEFAULT),
                ScreenStateKey.of(CARDS, ScreenState.VALIDATION_ERROR),
                ScreenStateKey.of(CARDS, ScreenState.BOTTOM_SHEET, "period_picker"),
                ScreenStateKey.of(CARDS, ScreenState.MODAL_DIALOG, "block_card")}) {
            repository.save(new StateSnapshot(key, Platform.IOS, "<hierarchy/>", Optional.empty(), CAPTURED));
        }
        repository.save(new StateSnapshot(ScreenStateKey.of(CARDS, ScreenState.EMPTY), Platform.ANDROID,
                "<hierarchy/>", Optional.empty(), CAPTURED));

        assertThat(repository.findKeys(CARDS, Platform.IOS)).containsExactlyInAnyOrder(
                ScreenStateKey.of(CARDS, ScreenState.DEFAULT),
                ScreenStateKey.of(CARDS, ScreenState.VALIDATION_ERROR),
                ScreenStateKey.of(CARDS, ScreenState.BOTTOM_SHEET, "period_picker"),
                ScreenStateKey.of(CARDS, ScreenState.MODAL_DIALOG, "block_card"));
        assertThat(repository.findKeys(CARDS, Platform.ANDROID))
                .containsExactly(ScreenStateKey.of(CARDS, ScreenState.EMPTY));
        assertThat(repository.findKeys(new ScreenId("Unknown"), Platform.IOS)).isEmpty();
    }

    @Test
    void unknownFileNamesAreIgnored() {
        assertThat(FileSnapshotRepository.parseKey(CARDS, "BOTTOM")).isEmpty();
        assertThat(FileSnapshotRepository.parseKey(CARDS, "DEFAULT_")).isEmpty();
    }

    @Test
    void unreadableSnapshotIsCorrupted() throws IOException {
        Path dir = Files.createDirectories(migrationDir.resolve("snapshots/CardsPage"));
        Files.write(dir.resolve("state_DEFAULT_ios_clean.xml"), new byte[]{(byte) 0xC3, (byte) 0x28});
        FileSnapshotRepository repository = new FileSnapshotRepository(migrationDir);

        assertThatThrownBy(() -> repository.find(ScreenStateKey.of(CARDS, ScreenState.DEFAULT), Platform.IOS))
                .isInstanceOf(CacheCorruptedException.class)
                .hasMessageContaining("UTF-8");
    }

    @Test
    void nonPngScreenshotsAreRejected() {
        FileSnapshotRepository repository = new FileSnapshotRepository(migrationDir);
        StateSnapshot jpeg = new StateSnapshot(ScreenStateKey.of(CARDS, ScreenState.DEFAULT), Platform.IOS,
                "<hierarchy/>", Optional.of(new ImageAttachment(new byte[]{1}, "image/jpeg")), CAPTURED);

        assertThatThrownBy(() -> repository.save(jpeg)).isInstanceOf(IllegalArgumentException.class);
    }
}
