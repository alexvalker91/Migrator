package alex.valker91.page;

import alex.valker91.core.MobilePlatform;
import alex.valker91.page.actions.PlatformActions;
import alex.valker91.page.actions.ScrollDirection;
import io.appium.java_client.AppiumDriver;
import io.appium.java_client.pagefactory.AppiumFieldDecorator;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.PageFactory;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;

/**
 * Cross-platform base of all Page Objects. The protected helpers below are the contract the migration engine
 * generates calls to ({@code FrameworkContract} in the engine): keep their names and signatures stable.
 * Platform differences live in {@link PlatformActions}, never in {@code if (iOS)} branches of Page Objects.
 */
public abstract class BasePage {

    private static final Duration ELEMENT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration LOADING_TIMEOUT = Duration.ofSeconds(30);

    protected AppiumDriver driver;
    protected String platformName;
    protected final MobilePlatform platform;
    private final PlatformActions actions;
    private BottomNavComponent bottomNav;

    public BasePage(AppiumDriver driver, String platformName) {
        this.driver = driver;
        this.platformName = platformName;
        this.platform = MobilePlatform.from(platformName);
        this.actions = PlatformActions.forPlatform(platform, driver);
        PageFactory.initElements(new AppiumFieldDecorator(driver, ELEMENT_TIMEOUT), this);
    }

    public BottomNavComponent getBottomNav() {
        if (bottomNav == null) {
            bottomNav = new BottomNavComponent(driver, platformName);
        }
        return bottomNav;
    }

    /** Waits until the global spinner ({@code ProgressBar} / {@code XCUIElementTypeActivityIndicator}) is gone. */
    protected void waitForLoadingToDisappear() {
        new WebDriverWait(driver, LOADING_TIMEOUT)
                .until(ExpectedConditions.invisibilityOfElementLocated(actions.loadingIndicator()));
    }

    /** Native scroll ({@code UiScrollable} / {@code mobile: scroll}) to the element with this text. */
    protected WebElement scrollToElementByText(String text) {
        return actions.scrollToElementByText(text);
    }

    /**
     * Native scroll inside a specific container: list, table, collection, bottom sheet or horizontal carousel.
     *
     * @param direction {@code up}, {@code down}, {@code left} or {@code right} (direction of the content)
     */
    protected void scrollInsideContainer(WebElement container, String direction) {
        actions.scrollInsideContainer(container, ScrollDirection.from(direction));
    }

    /** System BACK on Android, the back button of the navigation bar ({@code TopAppBar}) on iOS. */
    public void navigateBack() {
        actions.navigateBack();
    }

    /** Hides the keyboard if shown (iOS: toolbar Done/Return/Готово, then {@code mobile: hideKeyboard}). */
    protected void safeHideKeyboard() {
        actions.hideKeyboard();
    }

    protected void dismissBottomSheetBySwipe(WebElement sheet) {
        actions.dismissBottomSheet(sheet);
    }

    protected void openSystemNotifications() {
        actions.openSystemNotifications();
    }

    /**
     * Element text comparable across platforms: iOS falls back to {@code label}/{@code value}; non-breaking and
     * narrow non-breaking spaces (amounts, currencies) become regular spaces.
     */
    protected String getCleanText(WebElement element) {
        return normalizeSpaces(actions.text(element));
    }

    static String normalizeSpaces(String text) {
        return text.replace(' ', ' ').replace(' ', ' ').trim();
    }
}
