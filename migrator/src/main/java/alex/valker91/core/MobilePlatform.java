package alex.valker91.core;

/**
 * Platform the tests run on, parsed from {@code -Dplatform} / the TestNG {@code platform} suite parameter.
 */
public enum MobilePlatform {
    ANDROID("Android"),
    IOS("iOS");

    private final String value;

    MobilePlatform(String value) {
        this.value = value;
    }

    /** Canonical spelling passed to Page Object constructors and on the command line. */
    public String value() {
        return value;
    }

    public static MobilePlatform from(String value) {
        if (value != null) {
            for (MobilePlatform platform : values()) {
                if (platform.value.equalsIgnoreCase(value.trim())) {
                    return platform;
                }
            }
        }
        throw new IllegalArgumentException("Unknown platform '" + value + "', expected Android or iOS");
    }
}
