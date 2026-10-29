package alex.valker91.page.actions;

import io.appium.java_client.AppiumBy;
import io.appium.java_client.AppiumDriver;
import io.appium.java_client.HasOnScreenKeyboard;
import io.appium.java_client.HidesKeyboard;
import io.appium.java_client.android.HasNotifications;
import io.appium.java_client.android.nativekey.AndroidKey;
import io.appium.java_client.android.nativekey.KeyEvent;
import io.appium.java_client.android.nativekey.PressesKey;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.remote.RemoteWebElement;

import java.util.Map;

/**
 * UiAutomator2 implementation: {@code UiScrollable}, {@code mobile: scrollGesture}/{@code swipeGesture},
 * hardware BACK and the notification shade.
 */
final class AndroidActions implements PlatformActions {

    private static final double GESTURE_PERCENT = 0.75;

    private final AppiumDriver driver;

    AndroidActions(AppiumDriver driver) {
        this.driver = driver;
    }

    @Override
    public By loadingIndicator() {
        return By.className("android.widget.ProgressBar");
    }

    @Override
    public WebElement scrollToElementByText(String text) {
        String selector = "new UiScrollable(new UiSelector().scrollable(true))"
                + ".scrollIntoView(new UiSelector().text(\"" + escapeUiSelector(text) + "\"))";
        return driver.findElement(AppiumBy.androidUIAutomator(selector));
    }

    @Override
    public void scrollInsideContainer(WebElement container, ScrollDirection direction) {
        driver.executeScript("mobile: scrollGesture", Map.of(
                "elementId", ((RemoteWebElement) container).getId(),
                "direction", direction.value(),
                "percent", GESTURE_PERCENT));
    }

    @Override
    public void navigateBack() {
        ((PressesKey) driver).pressKey(new KeyEvent(AndroidKey.BACK));
    }

    @Override
    public void hideKeyboard() {
        if (((HasOnScreenKeyboard) driver).isKeyboardShown()) {
            ((HidesKeyboard) driver).hideKeyboard();
        }
    }

    @Override
    public void dismissBottomSheet(WebElement sheet) {
        driver.executeScript("mobile: swipeGesture", Map.of(
                "elementId", ((RemoteWebElement) sheet).getId(),
                "direction", ScrollDirection.DOWN.value(),
                "percent", GESTURE_PERCENT));
    }

    @Override
    public void openSystemNotifications() {
        ((HasNotifications) driver).openNotifications();
    }

    @Override
    public String text(WebElement element) {
        return element.getText();
    }

    static String escapeUiSelector(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
