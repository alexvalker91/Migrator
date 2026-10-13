package webdriverio;

import alex.valker91.page.HomeScreen;
import alex.valker91.page.LoginPage;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static org.testng.Assert.assertTrue;

public class LoginTest extends BaseTest {

    @DataProvider(name = "invalidEmails")
    public Object[][] getInvalidEmails() {
        return new Object[][]{
                {"testletters"},
                {"123456789"},
                {"!@#$%^&*()"},
                {"test@"},
                {"@domain.com"},
                {"test@@domain.com"},
                {"test @domain.com"}
        };
    }

    @Test(description = "Login negative test", dataProvider = "invalidEmails")
    public void loginNegativeTest(String invalidEmail) {
        String correctPassword = "correctPassword";

        LoginPage loginPage = new HomeScreen(driver, platformName)
                .getBottomNav()
                .openLogin();

        boolean isErrorDisplayed = loginPage
                .enterEmail(invalidEmail)
                .enterPassword(correctPassword)
                .clickLogin()
                .isInvalidEmailErrorDisplayed();

        assertTrue(isErrorDisplayed,
                "Validation error should be displayed for email: " + invalidEmail);
    }
}
