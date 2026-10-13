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

public class ScreenshotListener implements ITestListener {

    @Override
    public void onTestFailure(ITestResult result) {
        Object instance = result.getInstance();
        if (!(instance instanceof BaseTest baseTest)) return;

        AppiumDriver driver = baseTest.getDriver();
        if (driver == null) return;

        try {
            File src = ((TakesScreenshot) driver).getScreenshotAs(OutputType.FILE);
            Path dir = Path.of("target/screenshots");
            Files.createDirectories(dir);
            String fileName = result.getName() + "_" + System.currentTimeMillis() + ".png";
            Files.copy(src.toPath(), dir.resolve(fileName), StandardCopyOption.REPLACE_EXISTING);
            System.out.println("Screenshot saved: " + dir.resolve(fileName).toAbsolutePath());
        } catch (IOException e) {
            System.err.println("Failed to save screenshot: " + e.getMessage());
        }
    }
}
