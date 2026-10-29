package com.project.migration.application.agent;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FlatJsonTest {

    @Test
    void readsStringsEscapesAndScalars() {
        assertThat(FlatJson.parseStringObject(" {\"screen\": \"Login\", \"note\":\"a \\\"b\\\" \\u0041\\n\", \"n\": 5, \"t\": true } "))
                .containsExactly(Map.entry("screen", "Login"), Map.entry("note", "a \"b\" A\n"),
                        Map.entry("n", "5"), Map.entry("t", "true"));
        assertThat(FlatJson.parseStringObject("{}")).isEmpty();
    }

    @Test
    void rejectsAnythingThatIsNotAFlatObject() {
        for (String bad : new String[]{"", "[]", "{\"a\": {\"b\": 1}}", "{\"a\": [1]}", "{\"a\": \"x\"} tail",
                "{\"a\": \"x\"", "{a: 1}", "{\"a\" \"x\"}", "{\"a\": \"\\q\"}"}) {
            assertThatThrownBy(() -> FlatJson.parseStringObject(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
