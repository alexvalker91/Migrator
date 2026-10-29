package com.project.migration.domain.model.analysis;

/**
 * Platform-specific constructs in Android code that require Tier-1 adaptation in Stage 3.
 */
public enum ComplexityMarkerType {
    /** {@code UiScrollable} / {@code new UiSelector()} scrolling. */
    UI_SCROLLABLE,
    /** {@code AndroidKey.BACK} / {@code pressKey(new KeyEvent(AndroidKey.BACK))}. */
    ANDROID_KEY_BACK,
    /** {@code openNotifications()} and notification-shade handling. */
    OPEN_NOTIFICATIONS,
    /** {@code hideKeyboard()} or keyboard-dependent flows. */
    HIDE_KEYBOARD,
    /** Platform-only features with no 1:1 counterpart (Google Wallet / Google Pay vs Apple Pay). */
    PLATFORM_SPECIFIC_FEATURE,
    /** Any other Android-only API ({@code AndroidDriver} casts, {@code startActivity}, adb shell...). */
    ANDROID_ONLY_API,
    /**
     * Locator built in the method body ({@code driver.findElement(By.id(...))}, {@code AppiumBy...}) instead of
     * an annotated field: cannot get an {@code @iOSXCUITFindBy}, the method body is adapted as a whole.
     */
    INLINE_LOCATOR
}
