package alex.valker91.core.config;

import alex.valker91.core.MobilePlatform;
import org.aeonbits.owner.Accessible;
import org.aeonbits.owner.Config;

/**
 * Device and app settings. Extra capabilities are free-form prefixed keys read through {@link Accessible}:
 * {@code android.caps.*} / {@code ios.caps.*} always, {@code mobitru.caps.*} only on the Mobitru farm.
 */
@Config.Sources({
        "classpath:webdriverio/mobile.${env}.properties",
        "classpath:webdriverio/mobile.qa.properties" })
public interface WebdriverioConfig extends Config, Accessible {

    @Key("appium.url")
    String appiumUrl();

    /** Mobitru hub without credentials, e.g. {@code https://app.mobitru.com/wd/hub}. */
    @Key("mobitru.url")
    String mobitruUrl();

    /** User part of the Mobitru hub credentials (billing unit); the token comes from {@code MOBITRU_AUTH_TOKEN}. */
    @Key("mobitru.billing.unit")
    String mobitruBillingUnit();

    @Key("android.device.name")
    String androidDeviceName();

    @Key("android.platform.version")
    String androidPlatformVersion();

    @Key("android.app.package")
    String androidAppPackage();

    @Key("android.app.url")
    String androidAppUrl();

    @Key("ios.device.name")
    String iosDeviceName();

    @Key("ios.platform.version")
    String iosPlatformVersion();

    @Key("ios.bundle.id")
    String iosBundleId();

    @Key("ios.app.url")
    String iosAppUrl();

    /** Application id used for the fast restart ({@code terminateApp}/{@code activateApp}). */
    default String appId(MobilePlatform platform) {
        return switch (platform) {
            case ANDROID -> androidAppPackage();
            case IOS -> iosBundleId();
        };
    }
}
