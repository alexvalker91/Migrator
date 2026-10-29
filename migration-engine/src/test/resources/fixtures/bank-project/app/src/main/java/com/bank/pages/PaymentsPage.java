package com.bank.pages;

import com.bank.framework.BasePage;
import io.appium.java_client.AppiumDriver;
import io.appium.java_client.android.AndroidDriver;
import io.appium.java_client.pagefactory.AndroidFindBy;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;

import java.util.Map;

public class PaymentsPage extends BasePage {

    @AndroidFindBy(id = "com.bank:id/amount_input")
    private WebElement amountInput;

    public PaymentsPage(AppiumDriver driver, String platformName) {
        super(driver, platformName);
    }

    public PaymentsPage enterAmount(String amount) {
        amountInput.sendKeys(amount);
        ((AndroidDriver) driver).hideKeyboard();
        return this;
    }

    public GooglePayPage payWithGooglePay() {
        driver.findElement(By.id("com.bank:id/google_pay")).click();
        return new GooglePayPage(driver, platformName);
    }

    public PaymentsPage checkPushNotification() {
        ((AndroidDriver) driver).openNotifications();
        driver.executeScript("mobile: shell", Map.of("command", "input", "args", "keyevent 4"));
        return this;
    }
}
