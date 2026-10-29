package com.project.migration.application.agent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reader for the one JSON shape engine-defined tools take: a flat object whose values are strings. The
 * application layer has no JSON library, and these arguments are tiny.
 */
public final class FlatJson {

    private final String text;
    private int position;

    private FlatJson(String text) {
        this.text = text;
    }

    /**
     * @throws IllegalArgumentException if {@code json} is not an object of string (or scalar) values
     */
    public static Map<String, String> parseStringObject(String json) {
        FlatJson reader = new FlatJson(json == null ? "" : json);
        return reader.readObject();
    }

    private Map<String, String> readObject() {
        Map<String, String> values = new LinkedHashMap<>();
        skipWhitespace();
        expect('{');
        skipWhitespace();
        if (peek() == '}') {
            position++;
            return finish(values);
        }
        while (true) {
            skipWhitespace();
            String key = readString();
            skipWhitespace();
            expect(':');
            skipWhitespace();
            values.put(key, readValue());
            skipWhitespace();
            if (peek() == ',') {
                position++;
            } else {
                expect('}');
                return finish(values);
            }
        }
    }

    private Map<String, String> finish(Map<String, String> values) {
        skipWhitespace();
        if (position != text.length()) {
            throw new IllegalArgumentException("unexpected text after the JSON object at " + position);
        }
        return values;
    }

    private String readValue() {
        if (peek() == '"') {
            return readString();
        }
        int start = position;
        while (position < text.length() && ",}".indexOf(text.charAt(position)) < 0) {
            position++;
        }
        String scalar = text.substring(start, position).strip();
        if (scalar.isEmpty() || scalar.startsWith("{") || scalar.startsWith("[")) {
            throw new IllegalArgumentException("only string values are supported at " + start);
        }
        return scalar;
    }

    private String readString() {
        expect('"');
        StringBuilder value = new StringBuilder();
        while (true) {
            if (position >= text.length()) {
                throw new IllegalArgumentException("unterminated string");
            }
            char c = text.charAt(position++);
            if (c == '"') {
                return value.toString();
            }
            if (c != '\\') {
                value.append(c);
                continue;
            }
            if (position >= text.length()) {
                throw new IllegalArgumentException("unterminated escape");
            }
            char escaped = text.charAt(position++);
            switch (escaped) {
                case '"', '\\', '/' -> value.append(escaped);
                case 'n' -> value.append('\n');
                case 't' -> value.append('\t');
                case 'r' -> value.append('\r');
                case 'b' -> value.append('\b');
                case 'f' -> value.append('\f');
                case 'u' -> {
                    if (position + 4 > text.length()) {
                        throw new IllegalArgumentException("bad unicode escape");
                    }
                    value.append((char) Integer.parseInt(text.substring(position, position + 4), 16));
                    position += 4;
                }
                default -> throw new IllegalArgumentException("bad escape \\" + escaped);
            }
        }
    }

    private void expect(char expected) {
        if (peek() != expected) {
            throw new IllegalArgumentException("expected '" + expected + "' at " + position);
        }
        position++;
    }

    private char peek() {
        return position < text.length() ? text.charAt(position) : '\0';
    }

    private void skipWhitespace() {
        while (position < text.length() && Character.isWhitespace(text.charAt(position))) {
            position++;
        }
    }
}
