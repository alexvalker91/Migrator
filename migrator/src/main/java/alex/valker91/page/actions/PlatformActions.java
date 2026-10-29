package alex.valker91.page.actions;

import alex.valker91.core.MobilePlatform;
import io.appium.java_client.AppiumDriver;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;

/**
 * Native, platform-specific implementation behind the cross-platform helpers of {@code BasePage}.
 */
public interface PlatformActions {

    static PlatformActions forPlatform(MobilePlatform platform, AppiumDriver driver) {
        return switch (platform) {
            case ANDROID -> new AndroidActions(driver);
            case IOS -> new IosActions(driver);
        };
    }

    /** Global loading indicator (spinner) of the app. */
    By loadingIndicator();

    /** Scrolls the first scrollable container until an element with exactly this text is visible. */
    WebElement scrollToElementByText(String text);

    void scrollInsideContainer(WebElement container, ScrollDirection direction);

    void navigateBack();

    /** Hides the on-screen keyboard if it is shown; no-op otherwise. */
    void hideKeyboard();

    void dismissBottomSheet(WebElement sheet);

    void openSystemNotifications();

    /** Visible text of the element, as the platform exposes it. */
    String text(WebElement element);
}
