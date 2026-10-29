package com.bank.pages;

import io.appium.java_client.AppiumDriver;
import io.appium.java_client.pagefactory.AndroidFindBy;
import org.openqa.selenium.WebElement;

public class BottomNav {

    private final AppiumDriver driver;
    private final String platformName;

    @AndroidFindBy(accessibility = "Payments")
    private WebElement paymentsTab;

    public BottomNav(AppiumDriver driver, String platformName) {
        this.driver = driver;
        this.platformName = platformName;
    }

    public PaymentsPage openPayments() {
        paymentsTab.click();
        return new PaymentsPage(driver, platformName);
    }
}
