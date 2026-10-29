package com.bank.tests;

import com.bank.framework.BaseTest;
import com.bank.pages.CardsPage;
import com.bank.pages.Groups;
import com.bank.pages.HomePage;
import com.bank.pages.LoginPage;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;

public class CardsTest extends BaseTest {

    @BeforeMethod
    public void login() {
        new LoginPage(driver, platformName).loginAs("user@bank.com", "secret");
    }

    @DataProvider(name = "cards")
    public Object[][] cards() {
        return new Object[][]{{"1111"}, {"4242"}};
    }

    @Test(description = "Card details are shown", dataProvider = "cards")
    public void openCardDetails(String last4) {
        String title = openCardsFromHome()
                .scrollToCard(last4)
                .firstCardTitle();
        assertEquals(title, "Visa " + last4);
    }

    @Test(groups = {"smoke"})
    public void payWithGooglePay() {
        new HomePage(driver, platformName)
                .bottomNav()
                .openPayments()
                .enterAmount("10")
                .payWithGooglePay()
                .confirm();
    }

    @Test(groups = Groups.REGRESSION)
    public void backFromCards() {
        CardsPage cards = new HomePage(driver, platformName).openCards();
        cards.backToHome();
        cards.navigateBack();
    }

    @Test
    public void loginWithOverload() {
        new LoginPage(driver, platformName).enterEmail("a@b.c", true).enterEmail("x@y.z");
    }

    private CardsPage openCardsFromHome() {
        return new HomePage(driver, platformName).openCards();
    }
}
