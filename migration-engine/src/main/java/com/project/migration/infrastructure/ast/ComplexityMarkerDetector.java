package com.project.migration.infrastructure.ast;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.project.migration.domain.model.analysis.ComplexityMarker;
import com.project.migration.domain.model.analysis.ComplexityMarkerType;
import com.project.migration.domain.model.code.FrameworkContract;
import com.project.migration.domain.model.code.MethodRef;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Finds Android-specific constructs in a method body. Matching is by (simple) name: Appium/Selenium types are
 * not on the engine classpath, so resolution would add nothing for them.
 */
final class ComplexityMarkerDetector {

    private static final int DETAIL_MAX_LENGTH = 160;

    private static final Set<String> UI_AUTOMATOR_TYPES = Set.of("UiScrollable", "UiSelector");
    private static final Set<String> LOCATOR_FACTORIES = Set.of("By", "AppiumBy", "MobileBy");
    private static final Set<String> ANDROID_ONLY_TYPES = Set.of("AndroidDriver", "AndroidElement");
    private static final Set<String> ANDROID_ONLY_METHODS = Set.of(
            "startActivity", "currentActivity", "getCurrentPackage", "toggleWifi", "toggleData",
            "toggleAirplaneMode", "setNetworkConnection", "pressKeyCode", "longPressKeyCode");
    private static final Set<String> ANDROID_ONLY_MOBILE_COMMANDS = Set.of(
            "mobile: shell", "mobile: startActivity", "mobile: broadcast", "mobile: startService");
    private static final Set<String> KEYBOARD_METHODS = Set.of("hideKeyboard", "isKeyboardShown");

    private final ProjectSourceIndex index;
    private final FrameworkContract framework;

    ComplexityMarkerDetector(ProjectSourceIndex index, FrameworkContract framework) {
        this.index = index;
        this.framework = framework;
    }

    Set<ComplexityMarker> detect(MethodRef ref) {
        Optional<MethodDeclaration> method = index.type(ref.classFqcn()).flatMap(type -> SourceLookup.findMethod(type, ref));
        if (method.isEmpty() || method.get().getBody().isEmpty()) {
            return Set.of();
        }
        Set<ComplexityMarker> markers = new LinkedHashSet<>();
        method.get().getBody().get().walk(Node.TreeTraversal.PREORDER, node -> classify(node).ifPresent(
                type -> markers.add(new ComplexityMarker(type, ref, detail(node)))));
        return Set.copyOf(markers);
    }

    private Optional<ComplexityMarkerType> classify(Node node) {
        return switch (node) {
            case ObjectCreationExpr creation when UI_AUTOMATOR_TYPES.contains(creation.getType().getNameAsString()) ->
                    Optional.of(ComplexityMarkerType.UI_SCROLLABLE);
            case StringLiteralExpr literal when literal.getValue().contains("UiScrollable") ->
                    Optional.of(ComplexityMarkerType.UI_SCROLLABLE);
            case FieldAccessExpr access when access.getNameAsString().equals("BACK")
                    && access.getScope().toString().endsWith("AndroidKey") ->
                    Optional.of(ComplexityMarkerType.ANDROID_KEY_BACK);
            case MethodCallExpr call -> classifyCall(call);
            case ClassOrInterfaceType type when ANDROID_ONLY_TYPES.contains(type.getNameAsString()) ->
                    Optional.of(ComplexityMarkerType.ANDROID_ONLY_API);
            case ClassOrInterfaceType type when framework.isPlatformFeatureClass(type.getNameAsString()) ->
                    Optional.of(ComplexityMarkerType.PLATFORM_SPECIFIC_FEATURE);
            default -> Optional.empty();
        };
    }

    private static Optional<ComplexityMarkerType> classifyCall(MethodCallExpr call) {
        String name = call.getNameAsString();
        if (name.equals("openNotifications")) {
            return Optional.of(ComplexityMarkerType.OPEN_NOTIFICATIONS);
        }
        if (KEYBOARD_METHODS.contains(name)) {
            return Optional.of(ComplexityMarkerType.HIDE_KEYBOARD);
        }
        if (ANDROID_ONLY_METHODS.contains(name) || isAndroidOnlyMobileCommand(call)) {
            return Optional.of(ComplexityMarkerType.ANDROID_ONLY_API);
        }
        boolean locatorFactory = call.getScope()
                .filter(scope -> scope instanceof NameExpr scopeName
                        && LOCATOR_FACTORIES.contains(scopeName.getNameAsString()))
                .isPresent();
        return locatorFactory ? Optional.of(ComplexityMarkerType.INLINE_LOCATOR) : Optional.empty();
    }

    private static boolean isAndroidOnlyMobileCommand(MethodCallExpr call) {
        if (!call.getNameAsString().equals("executeScript") || call.getArguments().isEmpty()) {
            return false;
        }
        Expression command = call.getArgument(0);
        return command instanceof StringLiteralExpr literal && ANDROID_ONLY_MOBILE_COMMANDS.contains(literal.getValue());
    }

    private static String detail(Node node) {
        String oneLine = node.toString().replaceAll("\\s+", " ").trim();
        return oneLine.length() <= DETAIL_MAX_LENGTH ? oneLine : oneLine.substring(0, DETAIL_MAX_LENGTH - 3) + "...";
    }
}
