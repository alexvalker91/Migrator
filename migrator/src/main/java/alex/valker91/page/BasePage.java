package alex.valker91.page;

import io.appium.java_client.AppiumDriver;
import io.appium.java_client.pagefactory.AppiumFieldDecorator;
import org.openqa.selenium.support.PageFactory;

import java.time.Duration;

public abstract class BasePage {
    protected AppiumDriver driver;
    protected String platformName;
    private BottomNavComponent bottomNav;

    public BasePage(AppiumDriver driver, String platformName) {
        this.driver = driver;
        this.platformName = platformName;
        PageFactory.initElements(new AppiumFieldDecorator(driver, Duration.ofSeconds(10)), this);
    }

    public BottomNavComponent getBottomNav() {
        if (bottomNav == null) {
            bottomNav = new BottomNavComponent(driver, platformName);
        }
        return bottomNav;
    }
}
