package com.project.migration.infrastructure.mcp;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.migration.domain.model.llm.ToolSpec;
import com.project.migration.domain.model.mcp.McpToolResult;
import com.project.migration.domain.model.media.ImageAttachment;
import com.project.migration.infrastructure.json.JsonMappers;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Translation between the MCP SDK's wire types and the domain's provider-neutral ones.
 */
final class McpResultMapper {

    private static final ObjectMapper JSON = JsonMappers.llm();

    private McpResultMapper() {
    }

    static ToolSpec toToolSpec(McpSchema.Tool tool) {
        String description = tool.description() == null ? "" : tool.description();
        return new ToolSpec(tool.name(), description, write(tool.inputSchema()));
    }

    static McpToolResult toResult(McpSchema.CallToolResult result) {
        StringBuilder text = new StringBuilder();
        List<ImageAttachment> images = new ArrayList<>();
        for (McpSchema.Content content : result.content()) {
            if (content instanceof McpSchema.TextContent textContent) {
                if (!text.isEmpty()) {
                    text.append('\n');
                }
                text.append(textContent.text());
            } else if (content instanceof McpSchema.ImageContent image) {
                decodeImage(image.data(), image.mimeType()).ifPresent(images::add);
            }
        }
        return new McpToolResult(text.toString(), images, Boolean.TRUE.equals(result.isError()));
    }

    /** Arguments of a tool call; a blank string means no arguments. */
    static Map<String, Object> parseArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> arguments = JSON.readValue(argumentsJson, new TypeReference<>() { });
            return arguments == null ? Map.of() : arguments;
        } catch (JacksonException e) {
            throw new IllegalArgumentException("tool arguments are not a JSON object: " + e.getOriginalMessage(), e);
        }
    }

    /** Base64 image data, with or without a {@code data:} URL prefix. */
    static Optional<ImageAttachment> decodeImage(String base64, String mimeType) {
        String payload = base64.contains(",") ? base64.substring(base64.indexOf(',') + 1) : base64;
        try {
            byte[] bytes = Base64.getMimeDecoder().decode(payload.strip());
            return bytes.length == 0 ? Optional.empty()
                    : Optional.of(new ImageAttachment(bytes, mimeType == null || mimeType.isBlank()
                    ? ImageAttachment.PNG : mimeType));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static String write(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new IllegalStateException("Cannot serialize tool schema: " + e.getOriginalMessage(), e);
        }
    }
}
