package com.bank.pages;

import com.bank.framework.BasePage;
import io.appium.java_client.AppiumDriver;
import io.appium.java_client.pagefactory.AndroidFindBy;
import org.openqa.selenium.WebElement;

public class HomePage extends BasePage {

    @AndroidFindBy(accessibility = "cards-tile")
    private WebElement cardsTile;

    private final BottomNav bottomNav;

    public HomePage(AppiumDriver driver, String platformName) {
        super(driver, platformName);
        this.bottomNav = new BottomNav(driver, platformName);
    }

    public CardsPage openCards() {
        cardsTile.click();
        return new CardsPage(driver, platformName);
    }

    public BottomNav bottomNav() {
        return bottomNav;
    }

    public HomePage refresh() {
        new CardsPage(driver, platformName).backToHome();
        return this;
    }
}
