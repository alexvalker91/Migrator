package alex.valker91.core.driver;

import alex.valker91.core.MobilePlatform;
import alex.valker91.core.config.WebdriverioConfig;
import io.appium.java_client.AppiumDriver;
import io.appium.java_client.android.AndroidDriver;
import io.appium.java_client.android.options.UiAutomator2Options;
import io.appium.java_client.ios.IOSDriver;
import io.appium.java_client.ios.options.XCUITestOptions;
import org.openqa.selenium.MutableCapabilities;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Creates the Appium session: the Mobitru farm when {@code MOBITRU_AUTH_TOKEN} is set, a local Appium server
 * otherwise. Sessions use {@code noReset=true}; a clean state between tests comes from the app restart in
 * {@code BaseTest}.
 */
public final class DriverFactory {

    public static final String MOBITRU_TOKEN_ENV = "MOBITRU_AUTH_TOKEN";

    private static final String MOBITRU_CAPS_PREFIX = "mobitru.caps.";

    private final WebdriverioConfig config;
    private final Function<String, String> environment;

    public DriverFactory(WebdriverioConfig config) {
        this(config, System::getenv);
    }

    DriverFactory(WebdriverioConfig config, Function<String, String> environment) {
        this.config = config;
        this.environment = environment;
    }

    public AppiumDriver create(MobilePlatform platform) {
        URL endpoint = endpoint();
        return switch (platform) {
            case ANDROID -> new AndroidDriver(endpoint, androidOptions());
            case IOS -> new IOSDriver(endpoint, iosOptions());
        };
    }

    boolean usesMobitru() {
        String token = environment.apply(MOBITRU_TOKEN_ENV);
        return token != null && !token.isBlank();
    }

    URL endpoint() {
        if (!usesMobitru()) {
            return toUrl(require(config.appiumUrl(), "appium.url"));
        }
        URI hub = toUri(require(config.mobitruUrl(), "mobitru.url"));
        String userInfo = encode(require(config.mobitruBillingUnit(), "mobitru.billing.unit"))
                + ":" + encode(environment.apply(MOBITRU_TOKEN_ENV));
        return toUrl(hub.getScheme() + "://" + userInfo + "@" + hub.getRawAuthority() + hub.getRawPath());
    }

    UiAutomator2Options androidOptions() {
        UiAutomator2Options options = new UiAutomator2Options()
                .setDeviceName(config.androidDeviceName())
                .setPlatformVersion(config.androidPlatformVersion())
                .setAppPackage(config.androidAppPackage())
                .setNoReset(true);
        if (config.androidAppUrl() != null) {
            options.setApp(config.androidAppUrl());
        }
        applyExtraCapabilities(options, "android.caps.");
        return options;
    }

    XCUITestOptions iosOptions() {
        XCUITestOptions options = new XCUITestOptions()
                .setDeviceName(config.iosDeviceName())
                .setPlatformVersion(config.iosPlatformVersion())
                .setBundleId(config.iosBundleId())
                .setNoReset(true);
        if (config.iosAppUrl() != null) {
            options.setApp(config.iosAppUrl());
        }
        applyExtraCapabilities(options, "ios.caps.");
        return options;
    }

    private void applyExtraCapabilities(MutableCapabilities options, String platformPrefix) {
        capabilitiesWithPrefix(platformPrefix).forEach(options::setCapability);
        if (usesMobitru()) {
            capabilitiesWithPrefix(MOBITRU_CAPS_PREFIX).forEach(options::setCapability);
        }
    }

    private Map<String, Object> capabilitiesWithPrefix(String prefix) {
        return config.propertyNames().stream()
                .filter(key -> key.startsWith(prefix) && key.length() > prefix.length())
                .sorted()
                .collect(Collectors.toMap(
                        key -> key.substring(prefix.length()),
                        key -> typed(config.getProperty(key)),
                        (first, second) -> second,
                        LinkedHashMap::new));
    }

    /** {@code true}/{@code false} and integers become typed capabilities; everything else stays a string. */
    static Object typed(String value) {
        if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
            return Boolean.parseBoolean(value);
        }
        if (value.matches("-?\\d{1,9}")) {
            return Integer.parseInt(value);
        }
        return value;
    }

    private static String require(String value, String key) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing configuration property '" + key + "'");
        }
        return value;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static URI toUri(String value) {
        try {
            return new URI(value);
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Invalid URL: " + value, e);
        }
    }

    private static URL toUrl(String value) {
        try {
            return toUri(value).toURL();
        } catch (MalformedURLException | IllegalArgumentException e) {
            throw new IllegalStateException("Invalid URL: " + value, e);
        }
    }
}
