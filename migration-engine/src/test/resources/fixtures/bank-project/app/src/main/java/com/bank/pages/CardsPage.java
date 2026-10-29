package com.bank.pages;

import com.bank.framework.BasePage;
import io.appium.java_client.AppiumBy;
import io.appium.java_client.AppiumDriver;
import io.appium.java_client.android.AndroidDriver;
import io.appium.java_client.android.nativekey.AndroidKey;
import io.appium.java_client.android.nativekey.KeyEvent;
import io.appium.java_client.pagefactory.AndroidFindAll;
import io.appium.java_client.pagefactory.AndroidFindBy;
import io.appium.java_client.pagefactory.AndroidFindBys;
import org.openqa.selenium.WebElement;

import java.util.List;

public class CardsPage extends BasePage {

    @AndroidFindBys({
            @AndroidFindBy(id = "com.bank:id/cards_list"),
            @AndroidFindBy(className = "android.widget.TextView")
    })
    private List<WebElement> cardTitles;

    @AndroidFindAll({@AndroidFindBy(id = "com.bank:id/balance"), @AndroidFindBy(id = "com.bank:id/amount")})
    private WebElement balance;

    @AndroidFindBy(id = "com.bank:id/block_card")
    private WebElement blockCardButton, unblockCardButton;

    public CardsPage(AppiumDriver driver, String platformName) {
        super(driver, platformName);
    }

    public CardsPage scrollToCard(String last4) {
        driver.findElement(AppiumBy.androidUIAutomator(
                "new UiScrollable(new UiSelector().scrollable(true)).scrollIntoView(new UiSelector().textContains(\"" + last4 + "\"))"));
        return this;
    }

    public String firstCardTitle() {
        return getCleanText(cardTitles.get(0));
    }

    public String balance() {
        return balance.getText();
    }

    public HomePage backToHome() {
        ((AndroidDriver) driver).pressKey(new KeyEvent(AndroidKey.BACK));
        return new HomePage(driver, platformName).refresh();
    }

    public void blockCard() {
        blockCardButton.click();
    }
}
