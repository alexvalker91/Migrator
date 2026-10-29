package com.project.migration.testsupport;

import com.project.migration.domain.model.Platform;
import com.project.migration.domain.model.llm.ToolSpec;
import com.project.migration.domain.model.mcp.McpToolResult;
import com.project.migration.domain.model.mcp.RawPageSource;
import com.project.migration.domain.model.media.ImageAttachment;
import com.project.migration.domain.port.McpClientPort;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

/**
 * In-memory MCP session: tools are listed from {@link #tools}, answers come from {@link #handler}, the page
 * source is whatever {@link #pageSource} holds (a supplier, so scripts can change screens).
 */
public final class FakeMcp implements McpClientPort {

    public static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3, 4};

    private final Platform platform;
    public final List<ToolSpec> tools = new ArrayList<>();
    public final List<String> invocations = new ArrayList<>();
    public java.util.function.Supplier<String> pageSource = () -> "<hierarchy/>";
    public BiFunction<String, String, McpToolResult> handler = (tool, args) -> McpToolResult.success("ok " + tool);
    public int restarts;
    public boolean closed;
    public RuntimeException restartFailure;

    public FakeMcp(Platform platform, String... toolNames) {
        this.platform = platform;
        for (String name : toolNames) {
            tools.add(new ToolSpec(name, "fake " + name, "{\"type\":\"object\",\"properties\":{}}"));
        }
    }

    @Override
    public Platform platform() {
        return platform;
    }

    @Override
    public List<ToolSpec> listTools() {
        return List.copyOf(tools);
    }

    @Override
    public McpToolResult invokeTool(String toolName, String argumentsJson) {
        invocations.add(toolName + " " + argumentsJson);
        return handler.apply(toolName, argumentsJson);
    }

    @Override
    public RawPageSource getPageSource() {
        invocations.add("getPageSource");
        return new RawPageSource(platform, pageSource.get(), Instant.parse("2026-10-30T10:00:00Z"));
    }

    @Override
    public ImageAttachment takeScreenshot() {
        invocations.add("takeScreenshot");
        return ImageAttachment.png(PNG);
    }

    @Override
    public void restartApp() {
        if (restartFailure != null) {
            throw restartFailure;
        }
        restarts++;
    }

    @Override
    public void close() {
        closed = true;
    }
}
