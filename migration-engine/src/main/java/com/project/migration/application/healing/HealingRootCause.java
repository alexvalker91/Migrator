package com.project.migration.application.healing;

/**
 * What the Tier-1 model thinks broke the test on iOS.
 */
public enum HealingRootCause {
    /** The iOS locator is wrong or ambiguous. */
    LOCATOR,
    /** A spinner or skeleton is still shown, or content has not loaded. */
    LOADING_STATE,
    /** The keyboard hides the element. */
    KEYBOARD,
    /** The element is outside the visible area, the scroll did not reach it. */
    SCROLL,
    /** The assertion compares text that differs between platforms (label vs value, U+00A0, formatting). */
    ASSERTION_TEXT,
    /** The test user, a limit or an OTP is used up; code is fine. */
    TEST_DATA,
    /** The cause lies in a shared framework class ({@code BasePage}, {@code BaseTest}) that must not be changed. */
    SHARED_FRAMEWORK,
    /** The device, the session or the network failed. */
    ENVIRONMENT,
    OTHER
}
