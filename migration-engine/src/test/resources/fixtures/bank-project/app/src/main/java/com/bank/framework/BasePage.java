package com.bank.framework;

import io.appium.java_client.AppiumDriver;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;

public abstract class BasePage {

    protected final AppiumDriver driver;
    protected final String platformName;

    protected BasePage(AppiumDriver driver, String platformName) {
        this.driver = driver;
        this.platformName = platformName;
    }

    protected void waitForLoadingToDisappear() {
        driver.findElements(By.className("android.widget.ProgressBar"));
    }

    protected WebElement scrollToElementByText(String text) {
        return driver.findElement(By.xpath("//*[@text='" + text + "']"));
    }

    public void navigateBack() {
        driver.navigate().back();
    }

    protected String getCleanText(WebElement element) {
        return element.getText().replace(' ', ' ');
    }
}
