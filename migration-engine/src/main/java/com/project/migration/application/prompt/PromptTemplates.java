package com.project.migration.application.prompt;

import com.project.migration.domain.model.Checks;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prompt texts kept as classpath resources ({@code /prompts/<name>.md}) with {@code {{placeholder}}} slots.
 * Rendering is strict: a missing value, an unused value or a stray slot is a programming error, caught by tests.
 */
public final class PromptTemplates {

    private static final Pattern SLOT = Pattern.compile("\\{\\{([a-zA-Z][a-zA-Z0-9_]*)}}");

    private PromptTemplates() {
    }

    /**
     * @throws IllegalArgumentException if the template does not exist or the values do not match its slots
     */
    public static String render(String name, Map<String, String> values) {
        String template = load(Checks.notBlank(name, "name"));
        Set<String> slots = new TreeSet<>();
        Matcher matcher = SLOT.matcher(template);
        while (matcher.find()) {
            slots.add(matcher.group(1));
        }
        if (!slots.equals(new TreeSet<>(values.keySet()))) {
            throw new IllegalArgumentException("prompt '" + name + "' has slots " + slots + " but got values "
                    + new TreeSet<>(values.keySet()));
        }
        StringBuilder rendered = new StringBuilder();
        matcher.reset();
        while (matcher.find()) {
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(values.get(matcher.group(1))));
        }
        matcher.appendTail(rendered);
        return rendered.toString().strip();
    }

    private static String load(String name) {
        String resource = "/prompts/" + name + ".md";
        try (InputStream in = PromptTemplates.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("prompt template not found: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read prompt " + resource, e);
        }
    }
}
