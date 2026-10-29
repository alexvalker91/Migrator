package com.project.migration.domain.model.code;

/**
 * iOS locator strategies for {@code @iOSXCUITFindBy}, in priority order.
 */
public enum IosLocatorStrategy {
    ACCESSIBILITY_ID("accessibility"),
    NS_PREDICATE("iOSNsPredicate"),
    CLASS_CHAIN("iOSClassChain");

    private final String annotationAttribute;

    IosLocatorStrategy(String annotationAttribute) {
        this.annotationAttribute = annotationAttribute;
    }

    /** Attribute name of {@code @iOSXCUITFindBy} that carries the locator value. */
    public String annotationAttribute() {
        return annotationAttribute;
    }
}
