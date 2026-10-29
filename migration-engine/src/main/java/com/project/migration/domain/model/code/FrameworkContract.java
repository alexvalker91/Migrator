package com.project.migration.domain.model.code;

import com.project.migration.domain.model.Checks;

import java.util.List;
import java.util.Set;

/**
 * Contract between the engine and the target project's test framework. Generated code calls only the
 * {@code BasePage} helpers named here; the base classes are cross-platform already and therefore excluded from
 * call trees and migration.
 *
 * @param basePageFqcn                 base class of all Page Objects
 * @param baseTestFqcn                 base class of all test classes
 * @param platformFeatureClassPrefixes simple-name prefixes of classes for platform-only features
 *                                     ({@code GooglePay*}: no 1:1 iOS counterpart, tests get group markers)
 */
public record FrameworkContract(String basePageFqcn, String baseTestFqcn, Set<String> platformFeatureClassPrefixes) {

    public static final String WAIT_FOR_LOADING_TO_DISAPPEAR = "waitForLoadingToDisappear";
    public static final String SCROLL_TO_ELEMENT_BY_TEXT = "scrollToElementByText";
    public static final String SCROLL_INSIDE_CONTAINER = "scrollInsideContainer";
    public static final String NAVIGATE_BACK = "navigateBack";
    public static final String SAFE_HIDE_KEYBOARD = "safeHideKeyboard";
    public static final String DISMISS_BOTTOM_SHEET_BY_SWIPE = "dismissBottomSheetBySwipe";
    public static final String OPEN_SYSTEM_NOTIFICATIONS = "openSystemNotifications";
    public static final String GET_CLEAN_TEXT = "getCleanText";

    /** Names of the helpers; generated code may call only these on {@code BasePage}. */
    public static final Set<String> HELPER_NAMES = Set.of(
            WAIT_FOR_LOADING_TO_DISAPPEAR, SCROLL_TO_ELEMENT_BY_TEXT, SCROLL_INSIDE_CONTAINER, NAVIGATE_BACK,
            SAFE_HIDE_KEYBOARD, DISMISS_BOTTOM_SHEET_BY_SWIPE, OPEN_SYSTEM_NOTIFICATIONS, GET_CLEAN_TEXT);

    /** Signatures of the helpers, as given to the LLM when it adapts platform-specific code. */
    public static final List<String> HELPER_SIGNATURES = List.of(
            "void " + WAIT_FOR_LOADING_TO_DISAPPEAR + "()",
            "WebElement " + SCROLL_TO_ELEMENT_BY_TEXT + "(String text)",
            "void " + SCROLL_INSIDE_CONTAINER + "(WebElement container, String direction /* up|down|left|right */)",
            "void " + NAVIGATE_BACK + "()",
            "void " + SAFE_HIDE_KEYBOARD + "()",
            "void " + DISMISS_BOTTOM_SHEET_BY_SWIPE + "(WebElement sheet)",
            "void " + OPEN_SYSTEM_NOTIFICATIONS + "()",
            "String " + GET_CLEAN_TEXT + "(WebElement element)");

    /** Framework of the sandbox project ({@code migrator} module). */
    public static final FrameworkContract DEFAULT = new FrameworkContract(
            "alex.valker91.page.BasePage", "webdriverio.BaseTest", Set.of("GooglePay", "GoogleWallet"));

    public FrameworkContract {
        Checks.notBlank(basePageFqcn, "basePageFqcn");
        Checks.notBlank(baseTestFqcn, "baseTestFqcn");
        platformFeatureClassPrefixes = Set.copyOf(Checks.notNull(platformFeatureClassPrefixes,
                "platformFeatureClassPrefixes"));
    }

    public boolean isFrameworkBaseClass(String classFqcn) {
        return basePageFqcn.equals(classFqcn) || baseTestFqcn.equals(classFqcn);
    }

    public boolean isPlatformFeatureClass(String simpleClassName) {
        return platformFeatureClassPrefixes.stream().anyMatch(simpleClassName::startsWith);
    }
}
