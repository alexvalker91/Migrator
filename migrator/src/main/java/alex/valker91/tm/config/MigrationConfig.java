package alex.valker91.tm.config;

import java.time.Duration;

public class MigrationConfig {
    private MigrationConfig() {}

    public static final String PROJECT_ROOT = "/Users/Aliaksandr_Kreyer/projects/repositories/";
    public static final String ANDROID_CAPS = PROJECT_ROOT + "util/capabilities-android.json";
    public static final String IOS_CAPS = PROJECT_ROOT + "util/capabilities-ios.json";

    public static final String TESTS_DIR = PROJECT_ROOT + "Migrator/migrator/src/test/java/";
    public static final String PAGES_DIR = PROJECT_ROOT + "Migrator/migrator/src/main/java/";

    public static final String DIAL_HOST = "https://ai-proxy.lab.epam.com";
    public static final String DIAL_API_KEY = "dial-6w6i0c55hfjava7qbym7dzyy92a";
    public static final String PLANNER_MODEL_ID = "gpt-6.1-sol-2026-09-29";
    public static final String WORKER_MODEL_ID = "gpt-4.1-mini-2025-04-14";

    public static final Duration MCP_TIMEOUT = Duration.ofSeconds(120);
    public static final int MCP_BUFFER_SIZE = 10 * 1024 * 1024; // 10 МБ
}
