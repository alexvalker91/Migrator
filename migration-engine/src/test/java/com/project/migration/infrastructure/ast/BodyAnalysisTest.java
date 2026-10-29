package com.project.migration.infrastructure.ast;

import com.project.migration.domain.model.analysis.ComplexityMarkerType;
import com.project.migration.domain.model.code.BodyInspection;
import com.project.migration.domain.model.code.FieldRef;
import com.project.migration.domain.model.code.LocatorField;
import com.project.migration.domain.model.code.MethodRef;
import com.project.migration.domain.model.code.TestMethodRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static com.project.migration.infrastructure.ast.BankProjectFixture.CARDS_PAGE;
import static com.project.migration.infrastructure.ast.BankProjectFixture.CARDS_TEST;
import static com.project.migration.infrastructure.ast.BankProjectFixture.LOGIN_PAGE;
import static com.project.migration.infrastructure.ast.BankProjectFixture.PAYMENTS_PAGE;
import static org.assertj.core.api.Assertions.assertThat;

class BodyAnalysisTest {

    private static final MethodRef SCROLL_TO_CARD = MethodRef.of(CARDS_PAGE, "scrollToCard", "String");
    private static final MethodRef ENTER_AMOUNT = MethodRef.of(PAYMENTS_PAGE, "enterAmount", "String");

    @TempDir
    Path projectRoot;

    private JavaParserAstAdapter adapter;

    @BeforeEach
    void setUp() {
        BankProjectFixture.copyTo(projectRoot);
        adapter = BankProjectFixture.adapter(projectRoot);
    }

    @Test
    void acceptsABodyThatUsesDocumentedHelpers() {
        BodyInspection inspection = adapter.inspectBody(ENTER_AMOUNT, """
                amountInput.sendKeys(amount);
                safeHideKeyboard();
                return this;
                """);

        assertThat(inspection.isAcceptable()).isTrue();
        assertThat(inspection.problems()).isEmpty();
    }

    @Test
    void rejectsLeftoverAndroidConstructsUnknownCallsAndBrokenSyntax() {
        BodyInspection leftover = adapter.inspectBody(ENTER_AMOUNT, """
                amountInput.sendKeys(amount);
                ((AndroidDriver) driver).hideKeyboard();
                return this;
                """);
        BodyInspection hallucinated = adapter.inspectBody(ENTER_AMOUNT, "swipeUpUntilVisible(); return this;");
        BodyInspection broken = adapter.inspectBody(ENTER_AMOUNT, "amountInput.sendKeys(amount) return this;");

        assertThat(leftover.remainingMarkers()).contains(ComplexityMarkerType.HIDE_KEYBOARD);
        assertThat(hallucinated.invalidCalls()).containsExactly("swipeUpUntilVisible");
        assertThat(broken.syntaxError()).isPresent();
        assertThat(broken.problems()).contains("does not parse");
    }

    @Test
    void acceptsCallsToMethodsOfTheClassItselfButNotToUndocumentedFrameworkMethods() {
        BodyInspection own = adapter.inspectBody(ENTER_AMOUNT, "checkPushNotification(); return this;");
        BodyInspection unknownClass = adapter.inspectBody(MethodRef.of("com.bank.Missing", "x"), "return;");

        assertThat(own.invalidCalls()).isEmpty();
        assertThat(unknownClass.syntaxError()).isPresent();
    }

    @Test
    void findsTopLevelStatementsReadingAField() {
        assertThat(adapter.statementsUsingField(MethodRef.of(LOGIN_PAGE, "loginAs", "String", "String"),
                new FieldRef(LOGIN_PAGE, "passwordField")))
                .containsExactly("passwordField.sendKeys(password);");
        assertThat(adapter.statementsUsingField(MethodRef.of(LOGIN_PAGE, "enterEmail", "String", "boolean"),
                new FieldRef(LOGIN_PAGE, "emailField")))
                .hasSize(2)
                .anyMatch(statement -> statement.startsWith("if (clearFirst)"));
    }

    @Test
    void marksCollectionFieldsAndDetectsMarkersInTheTestBodyItself() {
        assertThat(adapter.findLocatorFields(MethodRef.of(CARDS_PAGE, "firstCardTitle")))
                .extracting(LocatorField::collection).containsExactly(true);
        assertThat(adapter.findLocatorFields(MethodRef.of(LOGIN_PAGE, "loginAs", "String", "String")))
                .extracting(LocatorField::collection).containsOnly(false);
        assertThat(adapter.detectComplexityMarkers(new TestMethodRef(CARDS_TEST, "openCardDetails"))).isEmpty();
        assertThat(adapter.detectComplexityMarkers(SCROLL_TO_CARD)).extracting(marker -> marker.type())
                .contains(ComplexityMarkerType.UI_SCROLLABLE);
    }
}
