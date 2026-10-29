package com.project.migration.application.agent;

import com.project.migration.application.pagesource.XmlPageSourceCleaner;
import com.project.migration.application.telemetry.TelemetryCollector;
import com.project.migration.domain.model.Checks;
import com.project.migration.domain.model.config.AgentLoopConfig;
import com.project.migration.domain.model.llm.ToolCall;
import com.project.migration.domain.model.llm.ToolSpec;
import com.project.migration.domain.model.mcp.McpToolResult;
import com.project.migration.domain.model.screen.CleaningResult;
import com.project.migration.domain.port.McpClientPort;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tools the model may use in one run: the whitelisted MCP tools of one platform plus engine-defined synthetic
 * ones. The whitelist is enforced on both listing and calling. Screen XML never reaches the model raw: the
 * page-source tool is answered from the cleaner, and every other result is cut to the configured size.
 */
public final class AgentToolbox {

    public static final String PAGE_SOURCE_TOOL = "appium_get_page_source";

    private final McpClientPort mcp;
    private final AgentLoopConfig config;
    private final XmlPageSourceCleaner cleaner;
    private final TelemetryCollector telemetry;
    private final Map<String, SyntheticTool> synthetic = new LinkedHashMap<>();

    public AgentToolbox(McpClientPort mcp, AgentLoopConfig config, XmlPageSourceCleaner cleaner,
                        TelemetryCollector telemetry) {
        this.mcp = Checks.notNull(mcp, "mcp");
        this.config = Checks.notNull(config, "config");
        this.cleaner = Checks.notNull(cleaner, "cleaner");
        this.telemetry = Checks.notNull(telemetry, "telemetry");
    }

    public AgentToolbox with(SyntheticTool tool) {
        if (synthetic.putIfAbsent(tool.spec().name(), tool) != null) {
            throw new IllegalArgumentException("synthetic tool registered twice: " + tool.spec().name());
        }
        return this;
    }

    /** Whitelisted MCP tools followed by the synthetic ones. */
    public List<ToolSpec> specs() {
        List<ToolSpec> specs = new ArrayList<>(mcp.listTools().stream()
                .filter(spec -> config.isAllowed(spec.name()) && !synthetic.containsKey(spec.name()))
                .toList());
        synthetic.values().forEach(tool -> specs.add(tool.spec()));
        return specs;
    }

    /**
     * Runs one tool call of the model. Unknown, forbidden and failing calls come back as error outcomes.
     *
     * @throws com.project.migration.domain.exception.McpException if the MCP session itself fails
     */
    public ToolOutcome execute(ToolCall call) {
        SyntheticTool own = synthetic.get(call.name());
        if (own != null) {
            return own.handler().apply(call.argumentsJson());
        }
        if (!config.isAllowed(call.name())) {
            return ToolOutcome.error("Tool '" + call.name() + "' is not available. Available tools: "
                    + specs().stream().map(ToolSpec::name).toList());
        }
        if (PAGE_SOURCE_TOOL.equals(call.name())) {
            return screenXml();
        }
        McpToolResult result = mcp.invokeTool(call.name(), call.argumentsJson());
        String text = cut(result.text());
        if (!result.images().isEmpty()) {
            text = (text.isBlank() ? "" : text + "\n") + "[" + result.images().size() + " image(s) omitted]";
        }
        if (text.isBlank()) {
            text = "(empty result)";
        }
        return result.isError() ? ToolOutcome.error(text) : ToolOutcome.text(text);
    }

    private ToolOutcome screenXml() {
        CleaningResult cleaned = cleaner.clean(mcp.getPageSource());
        telemetry.recordCleaning(cleaned);
        return ToolOutcome.screen(cut(cleaned.cleanedXml()));
    }

    private String cut(String text) {
        int limit = config.maxToolResultChars();
        return text.length() <= limit ? text
                : text.substring(0, limit) + "\n[truncated, " + (text.length() - limit) + " more characters]";
    }
}
