package com.project.migration.domain.model.screen;

import com.project.migration.domain.model.Checks;
import com.project.migration.domain.model.Platform;

/**
 * Identifies one capturable state of a screen. {@code variant} distinguishes several instances of the same
 * state on one screen (e.g. two different bottom sheets: {@code card_picker}, {@code period_picker}).
 */
public record ScreenStateKey(ScreenId screen, ScreenState state, String variant) {

    public static final String DEFAULT_VARIANT = "default";

    public ScreenStateKey {
        Checks.notNull(screen, "screen");
        Checks.notNull(state, "state");
        Checks.notBlank(variant, "variant");
        if (!SafeName.isSafe(variant)) {
            throw new IllegalArgumentException("variant must be a file-system safe name: " + variant);
        }
    }

    public static ScreenStateKey of(ScreenId screen, ScreenState state) {
        return new ScreenStateKey(screen, state, DEFAULT_VARIANT);
    }

    public static ScreenStateKey of(ScreenId screen, ScreenState state, String variant) {
        return new ScreenStateKey(screen, state, variant);
    }

    public boolean isDefaultVariant() {
        return DEFAULT_VARIANT.equals(variant);
    }

    /** {@code state_<STATE>[_<variant>]}; the variant is omitted for the default one. */
    public String fileStem() {
        return "state_" + state.name() + (isDefaultVariant() ? "" : "_" + variant);
    }

    /** E.g. {@code state_BOTTOM_SHEET_card_picker_ios_clean.xml}. */
    public String cleanXmlFileName(Platform platform) {
        return fileStem() + "_" + platform.fileToken() + "_clean.xml";
    }

    /** E.g. {@code state_DEFAULT_android.png}. */
    public String screenshotFileName(Platform platform) {
        return fileStem() + "_" + platform.fileToken() + ".png";
    }

    @Override
    public String toString() {
        return screen + "/" + state + (isDefaultVariant() ? "" : "[" + variant + "]");
    }
}
