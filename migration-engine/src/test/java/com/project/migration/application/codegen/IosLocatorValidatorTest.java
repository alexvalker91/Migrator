package com.project.migration.application.codegen;

import com.project.migration.domain.model.code.IosLocatorStrategy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IosLocatorValidatorTest {

    private static final List<XmlElement> SCREEN = XmlElement.parse("""
            <hierarchy platform="ios">
              <NavigationBar zone="TOP_APP_BAR" name="Cards">
                <Button name="Back" label="Cards"/>
              </NavigationBar>
              <Button name="cards-tile" label="Cards" enabled="false"/>
              <StaticText name="Visa 4242" offscreen="true"/>
              <StaticText name="Visa 1111"/>
              <TextField name="email" value="a@b.c"/>
              <Button name="Pay"/>
              <Button name="Pay now" label="Pay now"/>
            </hierarchy>""");

    @Test
    void accessibilityIdMustFindExactlyOneElement() {
        assertThat(validate(IosLocatorStrategy.ACCESSIBILITY_ID, "cards-tile", false).isValid()).isTrue();
        assertThat(validate(IosLocatorStrategy.ACCESSIBILITY_ID, "missing", false).problem()).hasValue(
                "matches no element of the iOS snapshot");
        IosLocatorValidator.Verdict twice = IosLocatorValidator.validate(
                XmlElement.parse("<h><Button name=\"x\"/><Button name=\"x\"/></h>"),
                IosLocatorStrategy.ACCESSIBILITY_ID, "x", false);
        assertThat(twice.problem()).hasValueSatisfying(p -> assertThat(p).contains("2 elements"));
        assertThat(IosLocatorValidator.validate(XmlElement.parse("<h><Button name=\"x\"/><Button name=\"x\"/></h>"),
                IosLocatorStrategy.ACCESSIBILITY_ID, "x", true).isValid()).isTrue();
    }

    @Test
    void predicatesSupportAndConditionsAndTheCommonOperators() {
        assertThat(validate(IosLocatorStrategy.NS_PREDICATE, "type == 'XCUIElementTypeButton' AND name == 'Pay'", false)
                .isValid()).isTrue();
        assertThat(validate(IosLocatorStrategy.NS_PREDICATE, "name BEGINSWITH 'Pay now'", false).isValid()).isTrue();
        assertThat(validate(IosLocatorStrategy.NS_PREDICATE, "label ENDSWITH[c] 'NOW'", false).isValid()).isTrue();
        assertThat(validate(IosLocatorStrategy.NS_PREDICATE, "name LIKE 'Visa ????'", false).problem())
                .hasValueSatisfying(p -> assertThat(p).contains("2 elements"));
        assertThat(validate(IosLocatorStrategy.NS_PREDICATE, "name LIKE 'Visa ????'", true).isValid()).isTrue();
        assertThat(validate(IosLocatorStrategy.NS_PREDICATE, "enabled == false", false).first().orElseThrow().name())
                .isEqualTo("cards-tile");
    }

    @Test
    void labelAndValueFoldedAwayByTheCleanerAreRestored() {
        assertThat(validate(IosLocatorStrategy.NS_PREDICATE, "label == 'Visa 1111'", false).isValid()).isTrue();
        assertThat(validate(IosLocatorStrategy.NS_PREDICATE, "value == 'a@b.c'", false).isValid()).isTrue();
        assertThat(validate(IosLocatorStrategy.NS_PREDICATE, "value == 'Visa 1111'", false).isValid()).isTrue();
    }

    @Test
    void unsupportedPredicateFormsAreRejectedWithTheAllowedForms() {
        for (String predicate : new String[]{"name == 'a' OR name == 'b'", "name MATCHES 'x'", "size > 3",
                "name == 'Pay' && enabled == true"}) {
            IosLocatorValidator.Verdict verdict = validate(IosLocatorStrategy.NS_PREDICATE, predicate, false);
            assertThat(verdict.isValid()).as(predicate).isFalse();
            assertThat(verdict.problem().orElseThrow()).as(predicate).contains("iOSClassChain");
        }
    }

    @Test
    void classChainsAreLimitedToTheTypeWithAnOptionalPredicate() {
        assertThat(validate(IosLocatorStrategy.CLASS_CHAIN, "**/XCUIElementTypeTextField", false).isValid()).isTrue();
        assertThat(validate(IosLocatorStrategy.CLASS_CHAIN, "**/XCUIElementTypeButton[`name == 'Pay'`]", false).isValid()).isTrue();
        assertThat(validate(IosLocatorStrategy.CLASS_CHAIN, "**/*[`name == 'email'`]", false).isValid()).isTrue();
        assertThat(validate(IosLocatorStrategy.CLASS_CHAIN, "**/XCUIElementTypeButton", false).problem())
                .hasValueSatisfying(p -> assertThat(p).contains("matches 4 elements"));
        for (String chain : new String[]{"**/XCUIElementTypeNavigationBar/XCUIElementTypeButton",
                "**/XCUIElementTypeButton[2]", "XCUIElementTypeButton"}) {
            assertThat(validate(IosLocatorStrategy.CLASS_CHAIN, chain, false).problem().orElseThrow())
                    .as(chain).contains("not supported");
        }
    }

    @Test
    void reportsTheOffscreenStateAndTheTextToScrollTo() {
        IosLocatorValidator.IosElement element = validate(IosLocatorStrategy.ACCESSIBILITY_ID, "Visa 4242", false)
                .first().orElseThrow();

        assertThat(element.offscreen()).isTrue();
        assertThat(element.visibleText()).isEqualTo("Visa 4242");
    }

    private static IosLocatorValidator.Verdict validate(IosLocatorStrategy strategy, String value, boolean collection) {
        return IosLocatorValidator.validate(SCREEN, strategy, value, collection);
    }
}
