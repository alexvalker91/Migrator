package alex.valker91.tm.device;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MobileDeviceSession implements AutoCloseable {

    private final String platform;
    private final McpSyncClient client;
    private final String capsPath;

    public MobileDeviceSession(String platform, String capsPath) {
        this.platform = platform;
        this.capsPath = capsPath;
        this.client = McpClientFactory.createClient(capsPath);
    }

    public void startSession() {
        try {
            System.out.println("-> [" + platform + "] Старт сессии Appium...");
            String capsJson = Files.readString(Path.of(capsPath));
            client.callTool(new McpSchema.CallToolRequest(
                    "appium_session_management",
                    Map.of("action", "create", "platform", platform, "capabilities", capsJson)
            ));
            System.out.println("-> [" + platform + "] Сессия успешно запущена!");
        } catch (IOException e) {
            throw new RuntimeException("Не удалось прочитать capabilities: " + capsPath, e);
        }
    }

    public String getPageSource() {
        McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest("appium_get_page_source", Map.of()));
        if (result.content() != null && !result.content().isEmpty()) {
            if (result.content().get(0) instanceof McpSchema.TextContent tc) {
                return tc.text();
            }
        }
        return result.content() != null ? result.content().toString() : "";
    }

    public void tap(String strategy, String selector) {
        String uuid = findElementUuid(strategy, selector);
        if (uuid != null) {
            client.callTool(new McpSchema.CallToolRequest("appium_gesture", Map.of("action", "tap", "elementUUID", uuid)));
        }
    }

    public void type(String strategy, String selector, String text) {
        String uuid = findElementUuid(strategy, selector);
        if (uuid != null) {
            client.callTool(new McpSchema.CallToolRequest("appium_set_value", Map.of("elementUUID", uuid, "text", text != null ? text : "")));
        }
    }

    public void assertVisible(String strategy, String selector) {
        findElementUuid(strategy, selector);
    }

    private String findElementUuid(String strategy, String selector) {
        try {
            McpSchema.CallToolResult res = client.callTool(new McpSchema.CallToolRequest(
                    "appium_find_element",
                    Map.of("strategy", strategy, "selector", selector)
            ));
            Matcher m = Pattern.compile("([a-f0-9\\-]{16,36})", Pattern.CASE_INSENSITIVE).matcher(res.content().toString());
            return m.find() ? m.group(1) : res.content().toString().replaceAll("[^a-zA-Z0-9\\-]", "");
        } catch (Exception e) {
            System.err.println("  [" + platform + " Warn] Элемент не найден: " + selector + " (" + e.getMessage() + ")");
            return null;
        }
    }

    @Override
    public void close() {
        try {
            System.out.println("Закрываем сессию Appium [" + platform + "]...");
            client.callTool(new McpSchema.CallToolRequest("appium_session_management", Map.of("action", "delete")));
            client.close();
            System.out.println("Сессия [" + platform + "] закрыта.");
        } catch (Exception e) {
            System.err.println("Ошибка при закрытии сессии [" + platform + "]: " + e.getMessage());
        }
    }
}
