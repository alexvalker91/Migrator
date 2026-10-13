package alex.valker91.page;

import io.appium.java_client.AppiumDriver;
import io.appium.java_client.pagefactory.AndroidFindBy;
import io.appium.java_client.pagefactory.AppiumFieldDecorator;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.PageFactory;

import java.time.Duration;

public class BottomNavComponent {

    private final AppiumDriver driver;
    private final String platformName;

    @AndroidFindBy(accessibility = "Home")
    private WebElement homeTab;

    @AndroidFindBy(accessibility = "Webview")
    private WebElement webviewTab;

    @AndroidFindBy(accessibility = "Login")
    private WebElement loginTab;

    @AndroidFindBy(accessibility = "Forms")
    private WebElement formsTab;

    @AndroidFindBy(accessibility = "Swipe")
    private WebElement swipeTab;

    @AndroidFindBy(accessibility = "Drag")
    private WebElement dragTab;

    public BottomNavComponent(AppiumDriver driver, String platformName) {
        this.driver = driver;
        this.platformName = platformName;
        PageFactory.initElements(new AppiumFieldDecorator(driver, Duration.ofSeconds(10)), this);
    }

    public LoginPage openLogin() {
        loginTab.click();
        return new LoginPage(driver, platformName);
    }

    public HomeScreen openHome() {
        homeTab.click();
        return new HomeScreen(driver, platformName);
    }
}