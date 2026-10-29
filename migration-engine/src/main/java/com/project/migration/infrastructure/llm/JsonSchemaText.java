package com.project.migration.infrastructure.llm;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.project.migration.infrastructure.json.JsonMappers;
import dev.langchain4j.model.chat.request.json.JsonAnyOfSchema;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNullSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonRawSchema;
import dev.langchain4j.model.chat.request.json.JsonReferenceSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;

/**
 * Standard JSON Schema text of a LangChain4j schema, for deployments that do not support
 * {@code response_format: json_schema} and get the schema in the system prompt instead.
 */
final class JsonSchemaText {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private JsonSchemaText() {
    }

    static String of(JsonSchemaElement root) {
        return toNode(root).toString();
    }

    private static JsonNode toNode(JsonSchemaElement element) {
        ObjectNode node = NODES.objectNode();
        if (element.description() != null) {
            node.put("description", element.description());
        }
        switch (element) {
            case JsonObjectSchema object -> {
                node.put("type", "object");
                ObjectNode properties = node.putObject("properties");
                object.properties().forEach((name, property) -> properties.set(name, toNode(property)));
                if (object.required() != null && !object.required().isEmpty()) {
                    ArrayNode required = node.putArray("required");
                    object.required().forEach(required::add);
                }
                if (object.additionalProperties() != null) {
                    node.put("additionalProperties", object.additionalProperties());
                }
                if (object.definitions() != null && !object.definitions().isEmpty()) {
                    ObjectNode defs = node.putObject("$defs");
                    object.definitions().forEach((name, definition) -> defs.set(name, toNode(definition)));
                }
            }
            case JsonArraySchema array -> {
                node.put("type", "array");
                node.set("items", toNode(array.items()));
            }
            case JsonStringSchema ignored -> node.put("type", "string");
            case JsonIntegerSchema ignored -> node.put("type", "integer");
            case JsonNumberSchema ignored -> node.put("type", "number");
            case JsonBooleanSchema ignored -> node.put("type", "boolean");
            case JsonNullSchema ignored -> node.put("type", "null");
            case JsonEnumSchema enumeration -> {
                node.put("type", "string");
                ArrayNode values = node.putArray("enum");
                enumeration.enumValues().forEach(values::add);
            }
            case JsonAnyOfSchema anyOf -> {
                ArrayNode options = node.putArray("anyOf");
                anyOf.anyOf().forEach(option -> options.add(toNode(option)));
            }
            case JsonReferenceSchema reference -> node.put("$ref", "#/$defs/" + reference.reference());
            case JsonRawSchema raw -> {
                return parse(raw.schema());
            }
            default -> throw new IllegalArgumentException("Unsupported schema element " + element.getClass());
        }
        return node;
    }

    private static JsonNode parse(String schema) {
        try {
            return JsonMappers.llm().readTree(schema);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Invalid raw schema: " + e.getOriginalMessage(), e);
        }
    }
}
