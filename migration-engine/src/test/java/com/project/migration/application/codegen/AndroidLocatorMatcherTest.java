package com.project.migration.application.codegen;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AndroidLocatorMatcherTest {

    private static final List<XmlElement> SCREEN = XmlElement.parse("""
            <hierarchy>
              <Button resource-id="com.bank:id/pay" text="Pay" content-desc="pay-button" class="android.widget.Button"/>
              <TextView text="Total"/>
            </hierarchy>""");

    @Test
    void evaluatesTheSimpleLocatorForms() {
        assertThat(AndroidLocatorMatcher.matches("id = \"com.bank:id/pay\"", SCREEN)).contains(true);
        assertThat(AndroidLocatorMatcher.matches("id = \"pay\"", SCREEN)).contains(true);
        assertThat(AndroidLocatorMatcher.matches("accessibility = \"pay-button\"", SCREEN)).contains(true);
        assertThat(AndroidLocatorMatcher.matches("text = \"Total\"", SCREEN)).contains(true);
        assertThat(AndroidLocatorMatcher.matches("className = \"android.widget.TextView\"", SCREEN)).contains(true);
        assertThat(AndroidLocatorMatcher.matches("id = \"com.bank:id/other\"", SCREEN)).contains(false);
    }

    @Test
    void givesNoAnswerForLocatorsThatNeedADevice() {
        assertThat(AndroidLocatorMatcher.matches("xpath = \"//Button\"", SCREEN)).isEqualTo(Optional.empty());
        assertThat(AndroidLocatorMatcher.matches("uiAutomator = \"new UiSelector()\"", SCREEN)).isEmpty();
        assertThat(AndroidLocatorMatcher.matches("@AndroidFindBys({@AndroidFindBy(id = \"a\"), @AndroidFindBy(id = \"b\")})",
                SCREEN)).isEmpty();
        assertThat(AndroidLocatorMatcher.matches("id = \"a\", text = \"b\"", SCREEN)).isEmpty();
    }
}
