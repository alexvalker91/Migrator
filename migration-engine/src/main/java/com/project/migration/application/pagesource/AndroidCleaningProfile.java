package com.project.migration.application.pagesource;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * UiAutomator2 page source rules. Only standard framework prefixes are shortened: custom views keep their
 * fully-qualified class, which {@code @AndroidFindBy(xpath = ...)} locators need. Resource ids of the app
 * itself are written without the {@code <package>:id/} prefix (the package is on the root); UiAutomator2
 * resolves such short ids against the app package, so they are valid locators as written.
 */
final class AndroidCleaningProfile implements CleaningProfile {

    private static final List<String> KEPT = List.of("resource-id", "text", "content-desc", "enabled", "scrollable");
    private static final Set<String> TEXT = Set.of("text", "content-desc");
    private static final List<String> SEMANTIC = List.of("resource-id", "text", "content-desc");

    static final String PACKAGE_ATTRIBUTE = "package";

    private static final Pattern STANDARD_PREFIX =
            Pattern.compile("^(android\\.widget\\.|android\\.view\\.|androidx\\.[a-z0-9.]*?\\.widget\\.)");

    private static final Set<String> TOOLBARS = Set.of("Toolbar", "MaterialToolbar", "ActionBarView");
    private static final Set<String> BOTTOM_NAVS = Set.of("BottomNavigationView", "NavigationBarView");
    private static final Set<String> SCROLLABLES = Set.of("ScrollView", "NestedScrollView", "HorizontalScrollView",
            "RecyclerView", "ListView", "GridView", "ViewPager");
    private static final Set<String> WRAPPERS = Set.of("android.view.ViewGroup", "android.widget.FrameLayout",
            "android.widget.LinearLayout", "android.widget.RelativeLayout",
            "androidx.constraintlayout.widget.ConstraintLayout",
            "androidx.coordinatorlayout.widget.CoordinatorLayout");

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
        return "hierarchy".equals(tag);
    }

    @Override
    public Map<String, String> rootAttributes(Element documentRoot) {
        return findPackage(documentRoot).map(pkg -> Map.of(PACKAGE_ATTRIBUTE, pkg)).orElse(Map.of());
    }

    @Override
    public void compact(Map<String, String> attributes, Map<String, String> rootAttributes) {
        String appPackage = rootAttributes.get(PACKAGE_ATTRIBUTE);
        String resourceId = attributes.get("resource-id");
        if (appPackage != null && resourceId != null && resourceId.startsWith(appPackage + ":id/")) {
            attributes.put("resource-id", resourceId.substring(appPackage.length() + ":id/".length()));
        }
    }

    @Override
    public boolean isHidden(Element element) {
        return "false".equals(element.getAttribute("displayed"));
    }

    @Override
    public boolean isSecureField(String tag, Element element) {
        return "true".equals(element.getAttribute("password"));
    }

    @Override
    public Optional<SemanticZone> zoneOf(String tag, Element element) {
        String simpleName = CleaningProfile.simpleName(tag);
        String resourceId = element.getAttribute("resource-id");
        if (TOOLBARS.contains(simpleName) || resourceId.endsWith(":id/toolbar")
                || resourceId.endsWith(":id/action_bar")) {
            return Optional.of(SemanticZone.TOP_APP_BAR);
        }
        if (BOTTOM_NAVS.contains(simpleName)) {
            return Optional.of(SemanticZone.BOTTOM_NAV_BAR);
        }
        if (simpleName.contains("BottomSheet") || resourceId.endsWith(":id/design_bottom_sheet")) {
            return Optional.of(SemanticZone.BOTTOM_SHEET);
        }
        return Optional.empty();
    }

    @Override
    public boolean isScrollableContainer(String tag) {
        return SCROLLABLES.contains(CleaningProfile.simpleName(tag));
    }

    @Override
    public boolean isCollapsibleWrapper(String tag) {
        return WRAPPERS.contains(tag);
    }

    @Override
    public String shortTag(String tag) {
        return STANDARD_PREFIX.matcher(tag).replaceFirst("");
    }

    /** Package of the first element that declares one: the app under test, not a system overlay. */
    private static Optional<String> findPackage(Element element) {
        String pkg = element.getAttribute(PACKAGE_ATTRIBUTE);
        if (!pkg.isBlank()) {
            return Optional.of(pkg);
        }
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element child) {
                Optional<String> found = findPackage(child);
                if (found.isPresent()) {
                    return found;
                }
            }
        }
        return Optional.empty();
    }
}
