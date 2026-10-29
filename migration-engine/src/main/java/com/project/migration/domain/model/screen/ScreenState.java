package com.project.migration.domain.model.screen;

/**
 * Visual state of a screen for which a pair of Android/iOS snapshots is captured.
 */
public enum ScreenState {
    /** Main state with loaded content. */
    DEFAULT,
    /** Spinner, skeleton or shimmer is shown. */
    LOADING,
    /** No data (no cards, templates, history). */
    EMPTY,
    /** Inline field error or error banner. */
    VALIDATION_ERROR,
    /** Bottom sheet opened over the screen. */
    BOTTOM_SHEET,
    /** System or custom popup / alert. */
    MODAL_DIALOG,
    /** Success screen or overlay after an operation. */
    SUCCESS
}
