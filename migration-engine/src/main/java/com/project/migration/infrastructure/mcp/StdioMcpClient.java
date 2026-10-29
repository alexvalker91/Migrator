package com.project.migration.infrastructure.mcp;

import com.project.migration.domain.exception.McpException;
import com.project.migration.domain.model.Platform;
import com.project.migration.domain.model.config.McpServerConfig;
import com.project.migration.domain.model.llm.ToolSpec;
import com.project.migration.domain.model.mcp.McpToolCall;
import com.project.migration.domain.model.mcp.McpToolResult;
import com.project.migration.domain.model.mcp.RawPageSource;
import com.project.migration.domain.model.media.ImageAttachment;
import com.project.migration.domain.port.McpClientPort;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.UnaryOperator;

/**
 * Appium MCP server of one platform, started as a stdio child process. Opening a client starts the process,
 * performs the MCP handshake and runs the configured startup calls (device selection, session creation).
 * Every tool call is bounded by {@code callTimeout}: a hung call comes back as a tool-level error instead of
 * blocking the pipeline.
 */
final class StdioMcpClient implements McpClientPort {

    static final String PAGE_SOURCE_TOOL = "appium_get_page_source";
    static final String SCREENSHOT_TOOL = "appium_screenshot";
    static final String APP_LIFECYCLE_TOOL = "appium_app_lifecycle";

    private static final Logger LOG = LoggerFactory.getLogger(StdioMcpClient.class);

    private final Platform platform;
    private final McpServerConfig config;
    private final Duration callTimeout;
    private final McpSyncClient client;
    private final ExecutorService calls = Executors.newVirtualThreadPerTaskExecutor();
    private boolean closed;

    private StdioMcpClient(Platform platform, McpServerConfig config, Duration callTimeout, McpSyncClient client) {
        this.platform = platform;
        this.config = config;
        this.callTimeout = callTimeout;
        this.client = client;
    }

    /**
     * Starts the server, handshakes and runs the startup calls.
     *
     * @throws McpException if any of that fails; the process is stopped again in that case
     */
    static StdioMcpClient open(Platform platform, McpServerConfig config, Duration callTimeout,
                               UnaryOperator<String> environment) {
        ServerParameters parameters = ServerParameters.builder(config.command())
                .args(config.args())
                .env(McpEnvironment.resolve(platform, config.env(), environment))
                .build();
        StdioClientTransport transport = new StdioClientTransport(parameters, McpJsonDefaults.getMapper());
        transport.setStdErrorHandler(line -> LOG.debug("[{} MCP] {}", platform, line));
        McpSyncClient sync = McpClient.sync(transport)
                .requestTimeout(config.startupTimeout())
                .initializationTimeout(config.startupTimeout())
                .build();
        StdioMcpClient opened = new StdioMcpClient(platform, config, callTimeout, sync);
        try {
            sync.initialize();
            for (McpToolCall call : config.startupCalls()) {
                McpToolResult result = opened.call(call.tool(), call.argumentsJson(), config.startupTimeout());
                if (result.isError()) {
                    throw new McpException(platform, "startup call " + call.tool() + " failed: " + result.text());
                }
            }
            return opened;
        } catch (McpException e) {
            opened.close();
            throw e;
        } catch (RuntimeException e) {
            opened.close();
            throw new McpException(platform, "cannot start MCP server '" + config.command() + " "
                    + String.join(" ", config.args()) + "': " + e.getMessage(), e);
        }
    }

    @Override
    public Platform platform() {
        return platform;
    }

    @Override
    public List<ToolSpec> listTools() {
        try {
            return client.listTools().tools().stream().map(McpResultMapper::toToolSpec).toList();
        } catch (RuntimeException e) {
            throw new McpException(platform, "cannot list tools: " + e.getMessage(), e);
        }
    }

    @Override
    public McpToolResult invokeTool(String toolName, String argumentsJson) {
        return call(toolName, argumentsJson, callTimeout);
    }

    @Override
    public RawPageSource getPageSource() {
        McpToolResult result = invokeTool(PAGE_SOURCE_TOOL, "{}");
        if (result.isError()) {
            throw new McpException(platform, "page source is not available: " + result.text());
        }
        String text = result.text();
        int start = text.indexOf('<');
        int end = text.lastIndexOf('>');
        if (start < 0 || end < start) {
            throw new McpException(platform, "page source is not XML: " + abbreviate(text));
        }
        return new RawPageSource(platform, text.substring(start, end + 1), Clock.systemUTC().instant());
    }

    @Override
    public ImageAttachment takeScreenshot() {
        McpToolResult result = invokeTool(SCREENSHOT_TOOL, "{\"returnRawBase64\": true}");
        if (result.isError()) {
            throw new McpException(platform, "screenshot failed: " + result.text());
        }
        if (!result.images().isEmpty()) {
            return result.images().getFirst();
        }
        return McpResultMapper.decodeImage(result.text(), ImageAttachment.PNG).orElseThrow(() ->
                new McpException(platform, "screenshot answer holds no image: " + abbreviate(result.text())));
    }

    @Override
    public void restartApp() {
        String appId = config.appId().orElseThrow(() -> new McpException(platform,
                "no app id configured, set mcp_servers." + platform.name() + ".app_id to restart the app"));
        for (String action : List.of("terminate", "activate")) {
            McpToolResult result = invokeTool(APP_LIFECYCLE_TOOL,
                    "{\"action\": \"" + action + "\", \"id\": \"" + appId + "\"}");
            if (result.isError()) {
                throw new McpException(platform, "cannot " + action + " " + appId + ": " + result.text());
            }
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        calls.shutdownNow();
        try {
            client.closeGracefully();
        } catch (RuntimeException e) {
            LOG.warn("[{} MCP] graceful close failed: {}", platform, e.getMessage());
        }
        try {
            client.close();
        } catch (RuntimeException e) {
            LOG.debug("[{} MCP] close: {}", platform, e.getMessage());
        }
    }

    private McpToolResult call(String toolName, String argumentsJson, Duration timeout) {
        Map<String, Object> arguments;
        try {
            arguments = McpResultMapper.parseArguments(argumentsJson);
        } catch (IllegalArgumentException e) {
            return McpToolResult.error(e.getMessage());
        }
        Future<McpSchema.CallToolResult> pending = calls.submit(
                () -> client.callTool(new McpSchema.CallToolRequest(toolName, arguments, null)));
        try {
            return McpResultMapper.toResult(pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS));
        } catch (TimeoutException e) {
            pending.cancel(true);
            return McpToolResult.error("tool " + toolName + " did not answer within " + timeout.toSeconds() + " s");
        } catch (InterruptedException e) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new McpException(platform, "interrupted while calling " + toolName, e);
        } catch (ExecutionException e) {
            throw new McpException(platform, "call of " + toolName + " failed: " + e.getCause().getMessage(), e.getCause());
        }
    }

    private static String abbreviate(String text) {
        String oneLine = text.replaceAll("\\s+", " ").strip();
        return oneLine.length() <= 200 ? oneLine : oneLine.substring(0, 197) + "...";
    }
}
