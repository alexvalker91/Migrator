package listener;

import io.appium.java_client.AppiumDriver;
import webdriverio.BaseTest;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.testng.ITestListener;
import org.testng.ITestResult;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Saves what the migration engine needs to diagnose a failed test:
 * {@code target/screenshots/<test>_<timestamp>.png} and {@code target/page-source/<test>_<timestamp>.xml}.
 * The file names are a contract with {@code MavenTestNgRunnerAdapter}; see README, "Failure evidence".
 */
public class ScreenshotListener implements ITestListener {

    static final Path SCREENSHOT_DIR = Path.of("target/screenshots");
    static final Path PAGE_SOURCE_DIR = Path.of("target/page-source");

    @Override
    public void onTestFailure(ITestResult result) {
        Object instance = result.getInstance();
        if (!(instance instanceof BaseTest baseTest)) return;

        AppiumDriver driver = baseTest.getDriver();
        if (driver == null) return;

        String stem = result.getName() + "_" + System.currentTimeMillis();
        saveScreenshot(driver, stem);
        savePageSource(driver, stem);
    }

    private static void saveScreenshot(AppiumDriver driver, String stem) {
        try {
            File src = ((TakesScreenshot) driver).getScreenshotAs(OutputType.FILE);
            Files.createDirectories(SCREENSHOT_DIR);
            Path target = SCREENSHOT_DIR.resolve(stem + ".png");
            Files.copy(src.toPath(), target, StandardCopyOption.REPLACE_EXISTING);
            System.out.println("Screenshot saved: " + target.toAbsolutePath());
        } catch (IOException e) {
            System.err.println("Failed to save screenshot: " + e.getMessage());
        }
    }

    private static void savePageSource(AppiumDriver driver, String stem) {
        try {
            Files.createDirectories(PAGE_SOURCE_DIR);
            Path target = PAGE_SOURCE_DIR.resolve(stem + ".xml");
            Files.writeString(target, driver.getPageSource());
            System.out.println("Page source saved: " + target.toAbsolutePath());
        } catch (IOException | RuntimeException e) {
            System.err.println("Failed to save page source: " + e.getMessage());
        }
    }
}
