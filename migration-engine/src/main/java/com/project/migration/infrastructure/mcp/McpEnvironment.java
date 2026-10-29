package com.project.migration.infrastructure.mcp;

import com.project.migration.domain.exception.McpException;
import com.project.migration.domain.model.Platform;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves {@code ${NAME}} references in the configured environment of an MCP server process from the engine's
 * own environment, so machine-specific paths and secrets stay out of the configuration files.
 */
final class McpEnvironment {

    private static final Pattern REFERENCE = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");

    private McpEnvironment() {
    }

    static Map<String, String> resolve(Platform platform, Map<String, String> configured, UnaryOperator<String> lookup) {
        Map<String, String> resolved = new LinkedHashMap<>();
        configured.forEach((key, value) -> resolved.put(key, expand(platform, key, value, lookup)));
        return resolved;
    }

    private static String expand(Platform platform, String key, String value, UnaryOperator<String> lookup) {
        Matcher matcher = REFERENCE.matcher(value);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String actual = lookup.apply(matcher.group(1));
            if (actual == null || actual.isBlank()) {
                throw new McpException(platform, "environment variable " + matcher.group(1)
                        + " is not set (needed by mcp_servers." + platform.name() + ".env." + key + ")");
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(actual));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
