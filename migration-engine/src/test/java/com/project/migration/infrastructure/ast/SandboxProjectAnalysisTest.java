package com.project.migration.infrastructure.ast;

import com.project.migration.domain.model.code.FieldRef;
import com.project.migration.domain.model.code.FrameworkContract;
import com.project.migration.domain.model.code.LocatorField;
import com.project.migration.domain.model.code.MethodRef;
import com.project.migration.domain.model.code.TestMethodRef;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Read-only analysis of the real sandbox target project ({@code ../migrator}) with the default contract.
 */
class SandboxProjectAnalysisTest {

    private static final String LOGIN_PAGE = "alex.valker91.page.LoginPage";
    private static final TestMethodRef LOGIN_NEGATIVE = new TestMethodRef("webdriverio.LoginTest", "loginNegativeTest");

    private final JavaParserAstAdapter adapter =
            new JavaParserAstAdapter(Path.of("..", "migrator"), List.of(), FrameworkContract.DEFAULT);

    @Test
    void findsTheLoginTestAndItsCallTreeWithoutBasePageMethods() {
        assertThat(adapter.listTestMethods("webdriverio.LoginTest")).containsExactly(LOGIN_NEGATIVE);
        assertThat(adapter.resolveCallTree(LOGIN_NEGATIVE)).containsExactly(
                MethodRef.of("alex.valker91.page.BottomNavComponent", "openLogin"),
                MethodRef.of(LOGIN_PAGE, "enterEmail", "String"),
                MethodRef.of(LOGIN_PAGE, "enterPassword", "String"),
                MethodRef.of(LOGIN_PAGE, "clickLogin"),
                MethodRef.of(LOGIN_PAGE, "isInvalidEmailErrorDisplayed"));
    }

    @Test
    void findsAndroidOnlyLocatorsOfTheLoginPage() {
        assertThat(adapter.findLocatorFields(MethodRef.of(LOGIN_PAGE, "isInvalidEmailErrorDisplayed")))
                .containsExactly(new LocatorField(new FieldRef(LOGIN_PAGE, "invalidEmailError"),
                        "xpath = \"//android.widget.TextView[@text='Please enter a valid email address']\"",
                        Optional.empty()));
    }
}
