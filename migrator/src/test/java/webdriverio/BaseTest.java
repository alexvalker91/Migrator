package webdriverio;

import alex.valker91.core.config.ConfigLoader;
import alex.valker91.core.config.WebdriverioConfig;
import io.appium.java_client.AppiumDriver;
import io.appium.java_client.InteractsWithApps;
import io.appium.java_client.android.AndroidDriver;
import io.appium.java_client.android.options.UiAutomator2Options;
import listener.ScreenshotListener;
import org.testng.annotations.*;

import java.net.MalformedURLException;
import java.net.URL;

@Listeners(ScreenshotListener.class)
public class BaseTest {
    protected AppiumDriver driver;
    protected String platformName;

    private WebdriverioConfig webdriverioConfig;

    public AppiumDriver getDriver() {
        return driver;
    }

    @BeforeClass
    @Parameters({"platform"})
    public void setUpClass(String platform) throws MalformedURLException {
        this.platformName = platform;
        webdriverioConfig = ConfigLoader.loadWebdriverioConfig();

        if (platform.equalsIgnoreCase("Android")) {
            String appUrl = webdriverioConfig.androidAppUrl();
            UiAutomator2Options options = new UiAutomator2Options()
                    .setApp(appUrl)
                    .setDeviceName(webdriverioConfig.androidDeviceName())
                    .setPlatformVersion(webdriverioConfig.androidPlatformVersion())
                    .setNoReset(true);
            options.setCapability("appium:shouldTerminateApp", true);
            driver = new AndroidDriver(new URL(webdriverioConfig.appiumUrl()), options);

        } else if (platform.equalsIgnoreCase("iOS")) {
            // TODO
        }
    }

    @BeforeMethod
    public void resetAppState() {
        if (platformName.equalsIgnoreCase("Android")) {
            ((InteractsWithApps) driver).terminateApp(webdriverioConfig.androidAppPackage());
            ((InteractsWithApps) driver).activateApp(webdriverioConfig.androidAppPackage());
        } else {
//            TODO
        }
    }

    @AfterClass(alwaysRun = true)
    public void tearDownClass() {
        if (driver != null) {
            driver.quit();
        }
    }
}
