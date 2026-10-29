package com.project.migration.application.pagesource;

import org.w3c.dom.Element;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * XCUITest page source rules. {@code label} is omitted when it equals {@code name}, and {@code value} when it
 * equals the (effective) label, so an omitted attribute means "same as the previous one".
 */
final class IosCleaningProfile implements CleaningProfile {

    private static final String PREFIX = "XCUIElementType";

    private static final List<String> KEPT = List.of("name", "label", "value", "enabled");
    private static final Set<String> TEXT = Set.of("name", "label", "value");
    private static final List<String> SEMANTIC = List.of("name", "label", "value");

    private static final Set<String> SCROLLABLES = Set.of(
            "XCUIElementTypeScrollView", "XCUIElementTypeTable", "XCUIElementTypeCollectionView");
    private static final Set<String> SECURE_FIELDS = Set.of("XCUIElementTypeSecureTextField");

    @Override
    public List<String> keptAttributes() {
        return KEPT;
    }

    @Override
    public Set<String> textAttributes() {
        return TEXT;
    }

    @Override
    public List<String> semanticAttributes() {
        return SEMANTIC;
    }

    @Override
    public boolean isDocumentWrapper(String tag) {
        return "AppiumAUT".equals(tag);
    }

    @Override
    public void compact(Map<String, String> attributes, Map<String, String> rootAttributes) {
        String name = attributes.get("name");
        String label = attributes.getOrDefault("label", name);
        if (label != null && label.equals(name)) {
            attributes.remove("label");
        }
        String value = attributes.get("value");
        if (value != null && value.equals(label)) {
            attributes.remove("value");
        }
    }

    @Override
    public boolean isHidden(Element element) {
        return "false".equals(element.getAttribute("visible"));
    }

    @Override
    public boolean isSecureField(String tag, Element element) {
        return SECURE_FIELDS.contains(tag);
    }

    @Override
    public Optional<SemanticZone> zoneOf(String tag, Element element) {
        return switch (tag) {
            case "XCUIElementTypeNavigationBar" -> Optional.of(SemanticZone.TOP_APP_BAR);
            case "XCUIElementTypeTabBar" -> Optional.of(SemanticZone.BOTTOM_NAV_BAR);
            case "XCUIElementTypeSheet" -> Optional.of(SemanticZone.BOTTOM_SHEET);
            default -> Optional.empty();
        };
    }

    @Override
    public boolean isScrollableContainer(String tag) {
        return SCROLLABLES.contains(tag);
    }

    @Override
    public boolean isCollapsibleWrapper(String tag) {
        return "XCUIElementTypeOther".equals(tag);
    }

    @Override
    public String shortTag(String tag) {
        return tag.startsWith(PREFIX) && tag.length() > PREFIX.length() ? tag.substring(PREFIX.length()) : tag;
    }
}
