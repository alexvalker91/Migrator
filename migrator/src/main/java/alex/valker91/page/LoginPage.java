package alex.valker91.page;

import io.appium.java_client.AppiumDriver;
import io.appium.java_client.pagefactory.AndroidFindBy;
import org.openqa.selenium.WebElement;

public class LoginPage extends BasePage {

    @AndroidFindBy(accessibility = "input-email")
    private WebElement emailField;

    @AndroidFindBy(accessibility = "input-password")
    private WebElement passwordField;

    @AndroidFindBy(accessibility = "button-LOGIN")
    private WebElement loginButton;

    @AndroidFindBy(xpath = "//android.widget.TextView[@text='Please enter a valid email address']")
    private WebElement invalidEmailError;

    public LoginPage(AppiumDriver driver, String platformName) {
        super(driver, platformName);
    }

    public LoginPage enterEmail(String email) {
        emailField.clear();
        emailField.sendKeys(email);
        return this;
    }

    public LoginPage enterPassword(String password) {
        passwordField.clear();
        passwordField.sendKeys(password);
        return this;
    }

    public LoginPage clickLogin() {
        loginButton.click();
        return this;
    }

    public boolean isInvalidEmailErrorDisplayed() {
        try {
            return invalidEmailError.isDisplayed();
        } catch (Exception e) {
            return false;
        }
    }
}
