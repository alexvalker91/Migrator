package com.bank.framework;

import io.appium.java_client.AppiumDriver;
import org.testng.annotations.BeforeMethod;

public class BaseTest {

    protected AppiumDriver driver;
    protected String platformName;

    @BeforeMethod(alwaysRun = true)
    public void restartApp() {
        driver.getPageSource();
    }
}
