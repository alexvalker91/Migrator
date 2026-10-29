package com.project.migration.application.codegen;

import com.project.migration.domain.model.code.IosLocatorStrategy;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks a proposed iOS locator against the cleaned iOS snapshot, without a device: the value must find
 * exactly one element (at least one for a list field). Only forms that can be evaluated on the cleaned tree are
 * accepted: accessibility id; NSPredicate conditions joined with {@code AND}; class chains of the shape
 * {@code **}{@code /Type} or {@code **}{@code /Type[`predicate`]}. Intermediate hierarchy levels and indexes are
 * not evaluable because the cleaner collapses layout wrappers, so such chains are rejected.
 */
final class IosLocatorValidator {

    static final String ALLOWED_FORMS = "accessibility: the element's name; iOSNsPredicate: conditions on "
            + "name, label, value, type, enabled joined with AND (operators ==, !=, CONTAINS, BEGINSWITH, ENDSWITH, "
            + "LIKE); iOSClassChain: only **/XCUIElementTypeX or **/XCUIElementTypeX[`predicate`] "
            + "(no indexes, no intermediate levels)";

    private static final Pattern CLASS_CHAIN = Pattern.compile("^\\*\\*/(\\*|XCUIElementType\\w+)(?:\\[`(.+)`])?$");
    private static final Pattern CONDITION = Pattern.compile(
            "\\s*(\\w+)\\s*(==|=|!=|CONTAINS|BEGINSWITH|ENDSWITH|LIKE)(\\[[cd]+])?\\s*('(?:[^'\\\\]|\\\\.)*'|\"(?:[^\"\\\\]|\\\\.)*\"|true|false|TRUE|FALSE)\\s*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern AND = Pattern.compile("\\s+AND\\s+", Pattern.CASE_INSENSITIVE);

    /**
     * @param problem why the locator is not acceptable; empty when valid
     * @param first   first matching element when valid
     */
    record Verdict(Optional<String> problem, Optional<IosElement> first, int matches) {
        boolean isValid() {
            return problem.isEmpty();
        }
    }

    /** Element of the cleaned iOS tree with the attributes the cleaner folded away restored. */
    record IosElement(String type, String name, String label, String value, boolean enabled, boolean offscreen) {
        static IosElement of(XmlElement element) {
            String name = element.attribute("name");
            String label = element.attributes().containsKey("label") ? element.attribute("label") : name;
            String value = element.attributes().containsKey("value") ? element.attribute("value") : label;
            return new IosElement("XCUIElementType" + element.tag(), name, label, value,
                    !"false".equals(element.attribute("enabled")), "true".equals(element.attribute("offscreen")));
        }

        /** Text a scroll helper can search for. */
        String visibleText() {
            return !label.isBlank() ? label : !name.isBlank() ? name : value;
        }
    }

    private IosLocatorValidator() {
    }

    static Verdict validate(List<XmlElement> iosTree, IosLocatorStrategy strategy, String value, boolean collection) {
        List<IosElement> elements = iosTree.stream().map(IosElement::of).toList();
        List<IosElement> matches;
        try {
            matches = switch (strategy) {
                case ACCESSIBILITY_ID -> elements.stream().filter(e -> e.name().equals(value)).toList();
                case NS_PREDICATE -> filter(elements, parsePredicate(value));
                case CLASS_CHAIN -> classChain(elements, value);
            };
        } catch (IllegalArgumentException e) {
            return new Verdict(Optional.of(e.getMessage()), Optional.empty(), 0);
        }
        if (matches.isEmpty()) {
            return new Verdict(Optional.of("matches no element of the iOS snapshot"), Optional.empty(), 0);
        }
        if (matches.size() > 1 && !collection) {
            return new Verdict(Optional.of("matches " + matches.size() + " elements, it must match exactly one"),
                    Optional.empty(), matches.size());
        }
        return new Verdict(Optional.empty(), Optional.of(matches.getFirst()), matches.size());
    }

    private static List<IosElement> classChain(List<IosElement> elements, String chain) {
        Matcher matcher = CLASS_CHAIN.matcher(chain.strip());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("class chain form is not supported; " + ALLOWED_FORMS);
        }
        String type = matcher.group(1);
        List<Condition> conditions = matcher.group(2) == null ? List.of() : parsePredicate(matcher.group(2));
        return filter(elements.stream().filter(e -> type.equals("*") || e.type().equals(type)).toList(), conditions);
    }

    private static List<IosElement> filter(List<IosElement> elements, List<Condition> conditions) {
        return elements.stream().filter(element -> conditions.stream().allMatch(c -> c.test(element))).toList();
    }

    private static List<Condition> parsePredicate(String predicate) {
        List<Condition> conditions = new java.util.ArrayList<>();
        for (String part : AND.split(predicate.strip())) {
            if (part.contains(" OR ") || part.contains(" or ")) {
                throw new IllegalArgumentException("OR is not supported in predicates; " + ALLOWED_FORMS);
            }
            Matcher matcher = CONDITION.matcher(part);
            if (!matcher.matches()) {
                throw new IllegalArgumentException("predicate condition '" + part.strip() + "' is not supported; "
                        + ALLOWED_FORMS);
            }
            conditions.add(Condition.of(matcher.group(1), matcher.group(2).toUpperCase(Locale.ROOT),
                    matcher.group(3) != null && matcher.group(3).contains("c"), unquote(matcher.group(4))));
        }
        return conditions;
    }

    private static String unquote(String literal) {
        if (literal.length() >= 2 && (literal.startsWith("'") || literal.startsWith("\""))) {
            return literal.substring(1, literal.length() - 1).replace("\\'", "'").replace("\\\"", "\"");
        }
        return literal.toLowerCase(Locale.ROOT);
    }

    private record Condition(String attribute, String operator, boolean ignoreCase, String literal) {

        static Condition of(String attribute, String operator, boolean ignoreCase, String literal) {
            if (!List.of("name", "label", "value", "type", "enabled").contains(attribute)) {
                throw new IllegalArgumentException("attribute '" + attribute + "' is not available in the snapshot; "
                        + ALLOWED_FORMS);
            }
            return new Condition(attribute, operator, ignoreCase, literal);
        }

        boolean test(IosElement element) {
            String actual = switch (attribute) {
                case "name" -> element.name();
                case "label" -> element.label();
                case "value" -> element.value();
                case "type" -> element.type();
                default -> String.valueOf(element.enabled());
            };
            String left = ignoreCase ? actual.toLowerCase(Locale.ROOT) : actual;
            String right = ignoreCase ? literal.toLowerCase(Locale.ROOT) : literal;
            return switch (operator) {
                case "==", "=" -> left.equals(right);
                case "!=" -> !left.equals(right);
                case "CONTAINS" -> left.contains(right);
                case "BEGINSWITH" -> left.startsWith(right);
                case "ENDSWITH" -> left.endsWith(right);
                default -> Pattern.compile(likeToRegex(right)).matcher(left).matches();
            };
        }

        private static String likeToRegex(String like) {
            StringBuilder regex = new StringBuilder();
            for (char c : like.toCharArray()) {
                switch (c) {
                    case '*' -> regex.append(".*");
                    case '?' -> regex.append('.');
                    default -> regex.append(Pattern.quote(String.valueOf(c)));
                }
            }
            return regex.toString();
        }
    }
}
