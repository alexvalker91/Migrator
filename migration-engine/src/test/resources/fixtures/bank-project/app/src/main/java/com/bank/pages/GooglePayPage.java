package com.bank.pages;

import com.bank.framework.BasePage;
import io.appium.java_client.AppiumDriver;
import io.appium.java_client.pagefactory.AndroidFindBy;
import org.openqa.selenium.WebElement;

public class GooglePayPage extends BasePage {

    @AndroidFindBy(id = "com.google.android.gms:id/pay_button")
    private WebElement payButton;

    public GooglePayPage(AppiumDriver driver, String platformName) {
        super(driver, platformName);
    }

    public void confirm() {
        payButton.click();
    }
}
