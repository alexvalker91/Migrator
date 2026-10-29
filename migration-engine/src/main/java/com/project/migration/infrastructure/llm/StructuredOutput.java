package com.project.migration.infrastructure.llm;

import com.fasterxml.jackson.core.JacksonException;
import com.project.migration.domain.exception.LlmCallException;
import com.project.migration.infrastructure.json.JsonMappers;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.service.output.JsonSchemas;

/**
 * JSON schema derivation and parsing for structured ({@code record}) responses.
 *
 * @param <T> the record type
 */
final class StructuredOutput<T> {

    private final Class<T> type;
    private final JsonSchema schema;

    private StructuredOutput(Class<T> type, JsonSchema schema) {
        this.type = type;
        this.schema = schema;
    }

    static <T> StructuredOutput<T> of(Class<T> type) {
        JsonSchema schema = JsonSchemas.jsonSchemaFrom(type).orElseThrow(() ->
                new LlmCallException("Cannot derive a JSON schema from " + type.getName()));
        return new StructuredOutput<>(type, schema);
    }

    /** {@code response_format: json_schema} for deployments that support it. */
    ResponseFormat responseFormat() {
        return ResponseFormat.builder().type(ResponseFormatType.JSON).jsonSchema(schema).build();
    }

    /** System-prompt instruction for deployments without native JSON-schema support. */
    String promptInstruction() {
        return "Respond with a single JSON object and nothing else (no prose, no code fences). "
                + "It must validate against this JSON Schema:\n" + JsonSchemaText.of(schema.rootElement());
    }

    /**
     * @throws IllegalArgumentException with the parser message if {@code text} is not a valid instance
     */
    T parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("the answer is empty");
        }
        try {
            T value = JsonMappers.llm().readValue(extractJson(text), type);
            if (value == null) {
                throw new IllegalArgumentException("the answer is JSON null");
            }
            return value;
        } catch (JacksonException e) {
            throw new IllegalArgumentException(e.getOriginalMessage(), e);
        }
    }

    /** Tolerates code fences and prose around the object: the outermost {@code {...}} is taken. */
    static String extractJson(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        return start >= 0 && end > start ? text.substring(start, end + 1) : text.strip();
    }
}
