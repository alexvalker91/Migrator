package com.project.migration.infrastructure.llm;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.project.migration.domain.exception.LlmCallException;
import com.project.migration.domain.model.llm.ToolSpec;
import com.project.migration.infrastructure.json.JsonMappers;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonRawSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MCP tool schemas → LangChain4j tools without loss: tool parameters must be a {@link JsonObjectSchema}, but each
 * property (and {@code $defs} entry) is passed as a {@link JsonRawSchema}, which is serialized verbatim, so
 * {@code anyOf}, {@code enum}, formats and nested objects reach the model unchanged.
 */
final class ToolSpecMapper {

    private static final Set<String> SUPPORTED_ROOT_KEYS = Set.of(
            "type", "properties", "required", "additionalProperties", "description", "$defs", "$schema", "title");

    private ToolSpecMapper() {
    }

    static List<ToolSpecification> toToolSpecifications(List<ToolSpec> tools) {
        return tools.stream().map(ToolSpecMapper::toToolSpecification).toList();
    }

    static ToolSpecification toToolSpecification(ToolSpec tool) {
        return ToolSpecification.builder()
                .name(tool.name())
                .description(tool.description())
                .parameters(parameters(tool))
                .build();
    }

    private static JsonObjectSchema parameters(ToolSpec tool) {
        JsonNode root;
        try {
            root = JsonMappers.llm().readTree(tool.parametersJsonSchema());
        } catch (JacksonException e) {
            throw new LlmCallException("Tool " + tool.name() + " has an invalid JSON schema: " + e.getOriginalMessage(), e);
        }
        if (!root.isObject() || (root.has("type") && !"object".equals(root.get("type").asText()))) {
            throw new LlmCallException("Tool " + tool.name() + " parameters must be a JSON object schema");
        }
        Iterator<String> keys = root.fieldNames();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!SUPPORTED_ROOT_KEYS.contains(key)) {
                throw new LlmCallException("Tool " + tool.name() + " uses unsupported root schema keyword '" + key + "'");
            }
        }

        JsonObjectSchema.Builder schema = JsonObjectSchema.builder();
        if (root.hasNonNull("description")) {
            schema.description(root.get("description").asText());
        }
        Iterator<Map.Entry<String, JsonNode>> properties = root.path("properties").fields();
        while (properties.hasNext()) {
            Map.Entry<String, JsonNode> property = properties.next();
            schema.addProperty(property.getKey(), JsonRawSchema.from(property.getValue().toString()));
        }
        List<String> required = new ArrayList<>();
        root.path("required").forEach(name -> required.add(name.asText()));
        if (!required.isEmpty()) {
            schema.required(required);
        }
        if (root.has("additionalProperties") && root.get("additionalProperties").isBoolean()) {
            schema.additionalProperties(root.get("additionalProperties").asBoolean());
        }
        Iterator<Map.Entry<String, JsonNode>> definitions = root.path("$defs").fields();
        if (definitions.hasNext()) {
            Map<String, JsonSchemaElement> defs = new LinkedHashMap<>();
            definitions.forEachRemaining(def -> defs.put(def.getKey(), JsonRawSchema.from(def.getValue().toString())));
            schema.definitions(defs);
        }
        return schema.build();
    }
}
