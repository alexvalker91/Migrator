package com.project.migration.infrastructure.mcp;

import ch.qos.logback.classic.Level;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * Stand-in for appium-mcp, started as a child process by the tests over stdio. Behaviour is steered by the
 * environment: {@code FAKE_LOG} (file with one line per tool call), {@code FAKE_PAGE_SOURCE_FILE},
 * {@code FAKE_FAIL_TOOL} (tool name that answers with a tool-level error).
 */
public final class FakeAppiumMcpServer {

    public static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 1, 2, 3};

    private FakeAppiumMcpServer() {
    }

    public static void main(String[] args) throws Exception {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).setLevel(Level.OFF);
        Map<String, String> env = System.getenv();
        var server = McpServer.sync(new StdioServerTransportProvider(McpJsonDefaults.getMapper()))
                .serverInfo("fake-appium", "0.0.1");
        for (String name : List.of("select_device", "appium_session_management", "appium_app_lifecycle",
                "appium_find_element", "appium_gesture", "appium_get_page_source", "appium_screenshot", "slow_tool")) {
            server.toolCall(McpSchema.Tool.builder(name, Map.of("type", "object", "properties", Map.of()))
                    .description("fake " + name).build(), (exchange, request) -> handle(env, name, request));
        }
        server.build();
        new CountDownLatch(1).await();
    }

    private static McpSchema.CallToolResult handle(Map<String, String> env, String tool, McpSchema.CallToolRequest request) {
        log(env, tool + " " + request.arguments());
        if (tool.equals(env.get("FAKE_FAIL_TOOL"))) {
            return McpSchema.CallToolResult.builder().content(List.of(new McpSchema.TextContent("boom")))
                    .isError(true).build();
        }
        return switch (tool) {
            case "appium_get_page_source" -> text(pageSource(env));
            case "appium_screenshot" -> McpSchema.CallToolResult.builder()
                    .content(List.of(new McpSchema.ImageContent(null, Base64.getEncoder().encodeToString(PNG),
                            "image/png", null))).build();
            case "slow_tool" -> {
                sleep(5_000);
                yield text("late");
            }
            default -> text("ok " + tool + " " + request.arguments());
        };
    }

    private static String pageSource(Map<String, String> env) {
        String file = env.get("FAKE_PAGE_SOURCE_FILE");
        try {
            return file == null ? "<hierarchy><node text=\"Hello\"/></hierarchy>"
                    : "Page source:\n" + Files.readString(Path.of(file)) + "\n";
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static McpSchema.CallToolResult text(String text) {
        return McpSchema.CallToolResult.builder().content(List.of(new McpSchema.TextContent(text))).build();
    }

    private static void log(Map<String, String> env, String line) {
        String file = env.get("FAKE_LOG");
        if (file == null) {
            return;
        }
        try {
            Files.writeString(Path.of(file), line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
