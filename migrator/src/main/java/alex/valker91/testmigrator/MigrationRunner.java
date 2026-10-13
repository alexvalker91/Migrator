package alex.valker91.testmigrator;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class MigrationRunner {

    private static final String ANDROID_CAPS_PATH = "/Users/Aliaksandr_Kreyer/projects/repositories/util/capabilities-android.json";
    private static final String IOS_CAPS_PATH = "/Users/Aliaksandr_Kreyer/projects/repositories/util/capabilities-ios.json";

    public static void main(String[] args) {
        MigrationRunner runner = new MigrationRunner();
        runner.migrateTest("LoginTest", "loginNegativeTest");
    }

    private void migrateTest(String testClassName, String testMethodName) {
        System.out.println("=== СТАРТ МИГРАЦИИ: " + testClassName + "#" + testMethodName + " ===");

        try (McpSyncClient androidClient = createAndroidClient()) {

            // 1. Читаем capabilities из файла
            String androidCapsJson = Files.readString(Path.of(ANDROID_CAPS_PATH));

            // 2. Создаем сессию в Appium MCP
            System.out.println("Создаем сессию Android...");
            McpSchema.CallToolResult sessionResult = androidClient.callTool(new McpSchema.CallToolRequest(
                    "appium_session_management",
                    Map.of(
                            "action", "create",
                            "platform", "android",
                            "capabilities", androidCapsJson
                    )
            ));
            System.out.println("Результат создания сессии: " + sessionResult.content());

            // 3. Получаем Page Source (правильное имя инструмента: appium_get_page_source)
            System.out.println("Получаем Page Source для Android...");
            String androidXml = getPageSource(androidClient);
            System.out.println("Длина полученного XML: " + androidXml.length() + " символов");
            System.out.println("Фрагмент XML:\n" + androidXml.substring(0, Math.min(500, androidXml.length())));

        } catch (Exception e) {
            System.err.println("Ошибка при выполнении миграции: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private String getPageSource(McpSyncClient client) {
        // Имя инструмента берем точное: appium_get_page_source
        McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(
                "appium_get_page_source",
                Map.of()
        ));
        return result.content().toString();
    }

    private McpSyncClient createAndroidClient() {
        ServerParameters params = ServerParameters.builder("npx")
                .args("-y", "appium-mcp@latest")
                .env(Map.of(
                        "CAPABILITIES_CONFIG", ANDROID_CAPS_PATH
                ))
                .build();
        McpJsonMapper jsonMapper = new JacksonMcpJsonMapper(new JsonMapper());

        // Передаем размер буфера побольше (10 МБ), чтобы большой XML не обрезался
        StdioClientTransport transport = new StdioClientTransport(params, jsonMapper, 10 * 1024 * 1024);

        McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(java.time.Duration.ofSeconds(120))
                .build();

        client.initialize();
        return client;
    }

    private McpSyncClient createIosClient() {
        ServerParameters params = ServerParameters.builder("npx")
                .args("-y", "appium-mcp@latest")
                .env(Map.of(
                        "CAPABILITIES_CONFIG", IOS_CAPS_PATH
                ))
                .build();
        McpJsonMapper jsonMapper = new JacksonMcpJsonMapper(new JsonMapper());
        StdioClientTransport transport = new StdioClientTransport(params, jsonMapper, 10 * 1024 * 1024);

        McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(java.time.Duration.ofSeconds(120))
                .build();

        client.initialize();
        return client;
    }
}
