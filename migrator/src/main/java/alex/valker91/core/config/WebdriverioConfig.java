package alex.valker91.core.config;

import org.aeonbits.owner.Config;

@Config.Sources({
        "classpath:webdriverio/mobile.${env}.properties",
        "classpath:webdriverio/mobile.qa.properties" })
public interface WebdriverioConfig extends Config {

    @Key("appium.url")
    String appiumUrl();

    @Key("android.device.name")
    String androidDeviceName();

    @Key("android.platform.version")
    String androidPlatformVersion();

    @Key("android.app.package")
    String androidAppPackage();

    @Key("android.app.url")
    String androidAppUrl();
}
