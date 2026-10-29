package com.project.migration.domain.port;

import com.project.migration.domain.exception.McpException;
import com.project.migration.domain.model.Platform;
import com.project.migration.domain.model.llm.ToolSpec;
import com.project.migration.domain.model.mcp.McpToolResult;
import com.project.migration.domain.model.mcp.RawPageSource;
import com.project.migration.domain.model.media.ImageAttachment;

import java.util.List;

/**
 * Session with the Appium MCP server of one platform. Generic tool access feeds the LLM agent loop;
 * page source and screenshots have dedicated methods because the pipeline needs them deterministically.
 *
 * <p>All methods throw {@link McpException} on transport/session failures only.</p>
 */
public interface McpClientPort extends AutoCloseable {

    Platform platform();

    /** Tools registered by the server, in a form directly exposable to the LLM. */
    List<ToolSpec> listTools();

    /** Invokes a tool; tool-level failures come back as {@code McpToolResult.isError()}. */
    McpToolResult invokeTool(String toolName, String argumentsJson);

    /** Current raw {@code pageSource}; clean it before storing or prompting. */
    RawPageSource getPageSource();

    ImageAttachment takeScreenshot();

    /**
     * Clean restart of the app under test (terminate + activate), as {@code BaseTest} does before every test.
     *
     * @throws McpException when no app id is configured for the platform or the restart fails
     */
    void restartApp();

    /** Ends the Appium session and stops the server process. Idempotent. */
    @Override
    void close();
}
