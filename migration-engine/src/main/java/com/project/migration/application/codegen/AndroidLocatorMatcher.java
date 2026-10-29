package com.project.migration.application.codegen;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Evaluates the simple Android locators ({@code id}, {@code accessibility}, {@code text}, {@code className}) on a
 * cleaned Android snapshot. Anything else (xpath, UiAutomator, {@code @AndroidFindBys} chains) cannot be judged
 * without a device and gives no answer.
 */
final class AndroidLocatorMatcher {

    private static final Pattern PAIR = Pattern.compile("(\\w+)\\s*=\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    private AndroidLocatorMatcher() {
    }

    /** Whether the locator finds an element in the snapshot; empty when the locator form is not evaluable. */
    static Optional<Boolean> matches(String locatorSource, List<XmlElement> androidTree) {
        if (locatorSource.strip().startsWith("@")) {
            return Optional.empty();
        }
        List<String[]> pairs = new ArrayList<>();
        Matcher matcher = PAIR.matcher(locatorSource);
        while (matcher.find()) {
            pairs.add(new String[]{matcher.group(1), matcher.group(2)});
        }
        if (pairs.size() != 1) {
            return Optional.empty();
        }
        String kind = pairs.getFirst()[0];
        String value = pairs.getFirst()[1];
        return switch (kind) {
            case "id" -> Optional.of(androidTree.stream().anyMatch(e -> {
                String id = e.attribute("resource-id");
                return id.equals(value) || id.endsWith("/" + value);
            }));
            case "accessibility" -> Optional.of(androidTree.stream().anyMatch(e -> e.attribute("content-desc").equals(value)));
            case "text" -> Optional.of(androidTree.stream().anyMatch(e -> e.attribute("text").equals(value)));
            case "className" -> Optional.of(androidTree.stream().anyMatch(e ->
                    e.attribute("class").equals(value) || value.endsWith("." + e.tag()) || value.equals(e.tag())));
            default -> Optional.empty();
        };
    }
}
