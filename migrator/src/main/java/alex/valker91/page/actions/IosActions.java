package alex.valker91.page.actions;

import io.appium.java_client.AppiumBy;
import io.appium.java_client.AppiumDriver;
import io.appium.java_client.HasOnScreenKeyboard;
import org.openqa.selenium.By;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.remote.RemoteWebElement;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * XCUITest implementation: {@code mobile: scroll}/{@code mobile: swipe}, the back button of the navigation
 * bar, keyboard toolbar buttons and a swipe from the top edge for Notification Center.
 */
final class IosActions implements PlatformActions {

    /** Keyboard dismiss buttons, including the Russian localisation of the app. */
    static final List<String> KEYBOARD_DISMISS_KEYS = List.of("Done", "Return", "Готово", "done", "return");

    private static final By NAVIGATION_BACK_BUTTON =
            AppiumBy.iOSClassChain("**/XCUIElementTypeNavigationBar/XCUIElementTypeButton[1]");
    /** Keyboard toolbar first (app-provided Done), then the keyboard's own return key. */
    private static final List<By> KEYBOARD_DISMISS_BUTTONS = List.of(
            AppiumBy.iOSClassChain("**/XCUIElementTypeToolbar/**/" + dismissButtonChain()),
            AppiumBy.iOSClassChain("**/XCUIElementTypeKeyboard/**/" + dismissButtonChain()));
    private static final Duration NOTIFICATION_SWIPE = Duration.ofMillis(600);

    private final AppiumDriver driver;

    IosActions(AppiumDriver driver) {
        this.driver = driver;
    }

    @Override
    public By loadingIndicator() {
        return By.className("XCUIElementTypeActivityIndicator");
    }

    @Override
    public WebElement scrollToElementByText(String text) {
        String predicate = textPredicate(text);
        driver.executeScript("mobile: scroll", Map.of(
                "direction", ScrollDirection.DOWN.value(),
                "predicateString", predicate));
        return driver.findElement(AppiumBy.iOSNsPredicateString(predicate));
    }

    @Override
    public void scrollInsideContainer(WebElement container, ScrollDirection direction) {
        driver.executeScript("mobile: scroll", Map.of(
                "elementId", ((RemoteWebElement) container).getId(),
                "direction", direction.value()));
    }

    @Override
    public void navigateBack() {
        List<WebElement> backButtons = driver.findElements(NAVIGATION_BACK_BUTTON);
        if (backButtons.isEmpty()) {
            throw new NoSuchElementException("No back button in the navigation bar (TopAppBar) of the current screen");
        }
        backButtons.getFirst().click();
    }

    @Override
    public void hideKeyboard() {
        if (!isKeyboardShown()) {
            return;
        }
        for (By dismissButton : KEYBOARD_DISMISS_BUTTONS) {
            List<WebElement> found = driver.findElements(dismissButton);
            if (!found.isEmpty()) {
                found.getFirst().click();
                return;
            }
        }
        driver.executeScript("mobile: hideKeyboard", Map.of("keys", KEYBOARD_DISMISS_KEYS));
        if (isKeyboardShown()) {
            tapOutside();
        }
    }

    @Override
    public void dismissBottomSheet(WebElement sheet) {
        driver.executeScript("mobile: swipe", Map.of(
                "elementId", ((RemoteWebElement) sheet).getId(),
                "direction", ScrollDirection.DOWN.value()));
    }

    @Override
    public void openSystemNotifications() {
        Dimension window = driver.manage().window().getSize();
        W3cGestures.swipe(driver,
                W3cGestures.relative(window, 0.25, 0.0),
                W3cGestures.relative(window, 0.25, 0.6),
                NOTIFICATION_SWIPE);
    }

    /** iOS {@code getText()} is empty for some element types: fall back to {@code label}, then {@code value}. */
    @Override
    public String text(WebElement element) {
        String text = element.getText();
        if (text == null || text.isBlank()) {
            text = element.getAttribute("label");
        }
        if (text == null || text.isBlank()) {
            text = element.getAttribute("value");
        }
        return text == null ? "" : text;
    }

    static String textPredicate(String text) {
        String literal = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        return "label == " + literal + " OR name == " + literal + " OR value == " + literal;
    }

    private static String dismissButtonChain() {
        String names = String.join(", ", KEYBOARD_DISMISS_KEYS.stream().map(key -> "'" + key + "'").toList());
        return "XCUIElementTypeButton[`name IN {" + names + "}`]";
    }

    private boolean isKeyboardShown() {
        return ((HasOnScreenKeyboard) driver).isKeyboardShown();
    }

    /** Status-bar height below the top edge: outside any input, above the navigation bar buttons. */
    private void tapOutside() {
        Dimension window = driver.manage().window().getSize();
        W3cGestures.tap(driver, W3cGestures.relative(window, 0.5, 0.03));
    }
}
