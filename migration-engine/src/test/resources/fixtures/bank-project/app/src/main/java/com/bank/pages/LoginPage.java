package com.bank.pages;

import com.bank.framework.BasePage;
import io.appium.java_client.AppiumDriver;
import io.appium.java_client.pagefactory.AndroidFindBy;
import io.appium.java_client.pagefactory.iOSXCUITFindBy;
import org.openqa.selenium.WebElement;

public class LoginPage extends BasePage {

    @AndroidFindBy(accessibility = "input-email")
    private WebElement emailField;

    // password is never logged
    @AndroidFindBy(id = "com.bank:id/password")
    @iOSXCUITFindBy(accessibility = "password")
    private WebElement passwordField;

    @AndroidFindBy(xpath = "//android.widget.Button[@text='LOGIN']")
    private WebElement loginButton;

    private final String unusedNote = "not a locator";

    public LoginPage(AppiumDriver driver, String platformName) {
        super(driver, platformName);
    }

    public LoginPage enterEmail(String email) {
        emailField.clear();
        emailField.sendKeys(email);
        return this;
    }

    public LoginPage enterEmail(String email, boolean clearFirst) {
        if (clearFirst) {
            this.emailField.clear();
        }
        emailField.sendKeys(email);
        return this;
    }

    public HomePage loginAs(String email, String password) {
        enterEmail(email);
        passwordField.sendKeys(password);
        loginButton.click();
        waitForLoadingToDisappear();
        return new HomePage(driver, platformName);
    }

    public LoginPage typeIntoEmail(String emailField) {
        System.out.println(emailField);
        return this;
    }
}
