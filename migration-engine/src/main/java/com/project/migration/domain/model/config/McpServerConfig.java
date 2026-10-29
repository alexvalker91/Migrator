package com.project.migration.domain.model.config;

import com.project.migration.domain.model.Checks;
import com.project.migration.domain.model.mcp.McpToolCall;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * How to start the Appium MCP server of one platform and open its session.
 *
 * @param env           extra environment of the server process; values may reference the engine's own
 *                      environment as {@code ${NAME}}
 * @param startupCalls  tools called right after the server is up, in order (device selection, session creation)
 * @param appId         Android package / iOS bundle id of the app under test, needed to restart it between runs
 * @param startupTimeout limit for process start, MCP handshake and every startup call
 */
public record McpServerConfig(
        String command,
        List<String> args,
        Map<String, String> env,
        List<McpToolCall> startupCalls,
        Optional<String> appId,
        Duration startupTimeout) {

    public McpServerConfig {
        Checks.notBlank(command, "command");
        args = List.copyOf(Checks.notNull(args, "args"));
        env = Map.copyOf(Checks.notNull(env, "env"));
        startupCalls = List.copyOf(Checks.notNull(startupCalls, "startupCalls"));
        appId = appId == null ? Optional.empty() : appId.filter(id -> !id.isBlank());
        Checks.notNull(startupTimeout, "startupTimeout");
        if (startupTimeout.isZero() || startupTimeout.isNegative()) {
            throw new IllegalArgumentException("startupTimeout must be positive");
        }
    }
}
