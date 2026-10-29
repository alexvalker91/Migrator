package com.project.migration.domain.model;

import com.project.migration.domain.model.cache.ScreenCacheEntry;
import com.project.migration.domain.model.cache.ScreenElementMapping;
import com.project.migration.domain.model.cache.VerificationStatus;
import com.project.migration.domain.model.code.FieldRef;
import com.project.migration.domain.model.code.IosLocatorStrategy;
import com.project.migration.domain.model.screen.CleaningResult;
import com.project.migration.domain.model.screen.ScreenId;
import com.project.migration.domain.model.screen.ScreenState;
import com.project.migration.domain.model.screen.ScreenStateKey;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScreenModelTest {

    private static final ScreenId LOGIN = new ScreenId("LoginPage");
    private static final Instant NOW = Instant.parse("2026-10-29T10:00:00Z");

    @Test
    void defaultVariantIsOmittedFromFileNames() {
        ScreenStateKey key = ScreenStateKey.of(LOGIN, ScreenState.DEFAULT);

        assertThat(key.cleanXmlFileName(Platform.ANDROID)).isEqualTo("state_DEFAULT_android_clean.xml");
        assertThat(key.cleanXmlFileName(Platform.IOS)).isEqualTo("state_DEFAULT_ios_clean.xml");
        assertThat(key.screenshotFileName(Platform.IOS)).isEqualTo("state_DEFAULT_ios.png");
    }

    @Test
    void namedVariantKeepsSeveralBottomSheetsApart() {
        ScreenStateKey cards = ScreenStateKey.of(LOGIN, ScreenState.BOTTOM_SHEET, "card_picker");
        ScreenStateKey period = ScreenStateKey.of(LOGIN, ScreenState.BOTTOM_SHEET, "period_picker");

        assertThat(cards.cleanXmlFileName(Platform.IOS)).isEqualTo("state_BOTTOM_SHEET_card_picker_ios_clean.xml");
        assertThat(cards).isNotEqualTo(period);
        assertThat(cards.cleanXmlFileName(Platform.IOS)).isNotEqualTo(period.cleanXmlFileName(Platform.IOS));
    }

    @Test
    void namesUsedInPathsMustBeFileSystemSafe() {
        assertThatThrownBy(() -> ScreenStateKey.of(LOGIN, ScreenState.MODAL_DIALOG, "../etc"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScreenId("Login Page")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cleaningResultComputesSavingsAndRatio() {
        CleaningResult result = CleaningResult.of("<Button/>", 20_000, 1_000);

        assertThat(result.savedTokens()).isEqualTo(19_000);
        assertThat(result.compressionRatio()).isEqualTo(20.0);
        assertThatThrownBy(() -> new CleaningResult("<a/>", 100, 10, 5)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void screenBecomesStableOnlyWhenEveryStateAndMappingIsVerified() {
        ScreenStateKey defaultState = ScreenStateKey.of(LOGIN, ScreenState.DEFAULT);
        ScreenElementMapping email = mapping("emailField", VerificationStatus.UNVERIFIED);

        ScreenCacheEntry entry = ScreenCacheEntry.empty(LOGIN, NOW)
                .withState(defaultState, VerificationStatus.VERIFIED_STABLE, NOW)
                .withMapping(email, NOW);
        assertThat(entry.status()).isEqualTo(VerificationStatus.UNVERIFIED);

        ScreenCacheEntry verified = entry.withMapping(email.withStatus(VerificationStatus.VERIFIED_STABLE), NOW);
        assertThat(verified.mappings()).hasSize(1);
        assertThat(verified.status()).isEqualTo(VerificationStatus.VERIFIED_STABLE);

        assertThat(ScreenCacheEntry.empty(LOGIN, NOW).status()).isEqualTo(VerificationStatus.UNVERIFIED);
    }

    @Test
    void screenEntryRejectsStatesOfOtherScreens() {
        ScreenStateKey foreign = ScreenStateKey.of(new ScreenId("HomeScreen"), ScreenState.DEFAULT);

        assertThatThrownBy(() -> ScreenCacheEntry.empty(LOGIN, NOW).withState(foreign, VerificationStatus.UNVERIFIED, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ScreenElementMapping mapping(String field, VerificationStatus status) {
        return new ScreenElementMapping(new FieldRef("alex.valker91.page.LoginPage", field),
                "accessibility = \"input-email\"", IosLocatorStrategy.ACCESSIBILITY_ID, "input-email", status);
    }
}
