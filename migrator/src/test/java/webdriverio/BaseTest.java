package webdriverio;

import alex.valker91.core.MobilePlatform;
import alex.valker91.core.config.ConfigLoader;
import alex.valker91.core.config.WebdriverioConfig;
import alex.valker91.core.driver.DriverFactory;
import io.appium.java_client.AppiumDriver;
import io.appium.java_client.InteractsWithApps;
import listener.ScreenshotListener;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Listeners;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;

/**
 * One Appium session per test class; a fast clean restart of the app before every test method.
 * <p>
 * Platform: {@code -Dplatform} first (single-method runs {@code -Dtest=Class#method} bypass the suite XML),
 * then the {@code platform} suite parameter. Suites filter the groups {@code android-only}, {@code ios-only}
 * and {@code ios-needs-fix}.
 */
@Listeners(ScreenshotListener.class)
public class BaseTest {

    public static final String PLATFORM_PROPERTY = "platform";

    protected AppiumDriver driver;
    protected String platformName;
    protected MobilePlatform platform;

    private WebdriverioConfig webdriverioConfig;

    public AppiumDriver getDriver() {
        return driver;
    }

    @BeforeClass(alwaysRun = true)
    @Parameters({PLATFORM_PROPERTY})
    public void setUpClass(@Optional String suitePlatform) {
        platform = resolvePlatform(System.getProperty(PLATFORM_PROPERTY), suitePlatform);
        platformName = platform.value();
        webdriverioConfig = ConfigLoader.loadWebdriverioConfig();
        driver = new DriverFactory(webdriverioConfig).create(platform);
    }

    @BeforeMethod(alwaysRun = true)
    public void resetAppState() {
        String appId = webdriverioConfig.appId(platform);
        InteractsWithApps apps = (InteractsWithApps) driver;
        apps.terminateApp(appId);
        apps.activateApp(appId);
    }

    @AfterClass(alwaysRun = true)
    public void tearDownClass() {
        if (driver != null) {
            driver.quit();
        }
    }

    static MobilePlatform resolvePlatform(String systemProperty, String suiteParameter) {
        if (systemProperty != null && !systemProperty.isBlank()) {
            return MobilePlatform.from(systemProperty);
        }
        if (suiteParameter != null && !suiteParameter.isBlank()) {
            return MobilePlatform.from(suiteParameter);
        }
        throw new IllegalStateException(
                "Platform is not set: pass -D" + PLATFORM_PROPERTY + "=Android|iOS or the suite parameter");
    }
}
