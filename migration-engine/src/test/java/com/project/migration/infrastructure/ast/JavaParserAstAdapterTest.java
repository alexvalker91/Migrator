package com.project.migration.infrastructure.ast;

import com.project.migration.domain.model.analysis.ComplexityMarker;
import com.project.migration.domain.model.analysis.ComplexityMarkerType;
import com.project.migration.domain.model.code.FieldRef;
import com.project.migration.domain.model.code.LocatorField;
import com.project.migration.domain.model.code.MethodRef;
import com.project.migration.domain.model.code.TestMethodRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static com.project.migration.infrastructure.ast.BankProjectFixture.BOTTOM_NAV;
import static com.project.migration.infrastructure.ast.BankProjectFixture.CARDS_PAGE;
import static com.project.migration.infrastructure.ast.BankProjectFixture.CARDS_TEST;
import static com.project.migration.infrastructure.ast.BankProjectFixture.CLASS_LEVEL_TEST;
import static com.project.migration.infrastructure.ast.BankProjectFixture.GOOGLE_PAY_PAGE;
import static com.project.migration.infrastructure.ast.BankProjectFixture.HOME_PAGE;
import static com.project.migration.infrastructure.ast.BankProjectFixture.LOGIN_PAGE;
import static com.project.migration.infrastructure.ast.BankProjectFixture.PAYMENTS_PAGE;
import static org.assertj.core.api.Assertions.assertThat;

class JavaParserAstAdapterTest {

    @TempDir
    Path projectRoot;

    private JavaParserAstAdapter adapter;

    @BeforeEach
    void setUp() throws IOException {
        BankProjectFixture.copyTo(projectRoot);
        Path generated = projectRoot.resolve("app/target/generated-sources/src/main/java/com/bank/gen/Generated.java");
        Files.createDirectories(generated.getParent());
        Files.writeString(generated, "package com.bank.gen; public class Generated { }");
        adapter = BankProjectFixture.adapter(projectRoot);
    }

    @Test
    void discoversMainAndTestSourceRootsAndSkipsBuildOutput() {
        assertThat(adapter.sourceRoots()).containsExactly(
                projectRoot.resolve("app/src/main/java").toAbsolutePath().normalize(),
                projectRoot.resolve("app/src/test/java").toAbsolutePath().normalize());
        assertThat(adapter.sourceFileOf("com.bank.gen.Generated")).isEmpty();
        assertThat(adapter.sourceFileOf(LOGIN_PAGE)).contains(
                BankProjectFixture.sourceOf(projectRoot, LOGIN_PAGE, false).toAbsolutePath().normalize());
    }

    @Test
    void listsTestNgTestsInSourceOrderExcludingConfigurationMethods() {
        assertThat(adapter.listTestMethods(CARDS_TEST)).extracting(TestMethodRef::methodName)
                .containsExactly("openCardDetails", "payWithGooglePay", "backFromCards", "loginWithOverload");
    }

    @Test
    void classLevelTestAnnotationMakesPublicMethodsTests() {
        assertThat(adapter.listTestMethods(CLASS_LEVEL_TEST)).extracting(TestMethodRef::methodName)
                .containsExactly("implicitTestOne", "explicitTest");
    }

    @Test
    void listsAllTestsOfTheProject() {
        assertThat(adapter.listAllTestMethods()).extracting(TestMethodRef::key).containsExactly(
                CARDS_TEST + "#openCardDetails", CARDS_TEST + "#payWithGooglePay", CARDS_TEST + "#backFromCards",
                CARDS_TEST + "#loginWithOverload", CLASS_LEVEL_TEST + "#implicitTestOne",
                CLASS_LEVEL_TEST + "#explicitTest");
    }

    @Test
    void unknownClassesYieldEmptyResults() {
        assertThat(adapter.listTestMethods("com.bank.Missing")).isEmpty();
        assertThat(adapter.resolveCallTree(new TestMethodRef("com.bank.Missing", "x"))).isEmpty();
        assertThat(adapter.findLocatorFields(MethodRef.of("com.bank.Missing", "x"))).isEmpty();
        assertThat(adapter.detectComplexityMarkers(MethodRef.of(LOGIN_PAGE, "missing"))).isEmpty();
        assertThat(adapter.methodSource(MethodRef.of(LOGIN_PAGE, "missing"))).isEmpty();
        assertThat(adapter.sourceFileOf("com.bank.Missing")).isEmpty();
    }

    @Test
    void callTreeFollowsFluentChainsAndPrivateHelpersInExecutionOrder() {
        assertThat(adapter.resolveCallTree(test("openCardDetails"))).containsExactly(
                MethodRef.of(CARDS_TEST, "openCardsFromHome"),
                MethodRef.of(HOME_PAGE, "openCards"),
                MethodRef.of(CARDS_PAGE, "scrollToCard", "String"),
                MethodRef.of(CARDS_PAGE, "firstCardTitle"));
    }

    @Test
    void callTreeCrossesComponentsThatAreNotPages() {
        assertThat(adapter.resolveCallTree(test("payWithGooglePay"))).containsExactly(
                MethodRef.of(HOME_PAGE, "bottomNav"),
                MethodRef.of(BOTTOM_NAV, "openPayments"),
                MethodRef.of(PAYMENTS_PAGE, "enterAmount", "String"),
                MethodRef.of(PAYMENTS_PAGE, "payWithGooglePay"),
                MethodRef.of(GOOGLE_PAY_PAGE, "confirm"));
    }

    @Test
    void callTreeIsCycleSafeAndExcludesBasePageMethods() {
        assertThat(adapter.resolveCallTree(test("backFromCards"))).containsExactly(
                MethodRef.of(HOME_PAGE, "openCards"),
                MethodRef.of(CARDS_PAGE, "backToHome"),
                MethodRef.of(HOME_PAGE, "refresh"));
    }

    @Test
    void callTreeDistinguishesOverloads() {
        assertThat(adapter.resolveCallTree(test("loginWithOverload"))).containsExactly(
                MethodRef.of(LOGIN_PAGE, "enterEmail", "String", "boolean"),
                MethodRef.of(LOGIN_PAGE, "enterEmail", "String"));
    }

    @Test
    void findsLocatorFieldsInOrderOfFirstUseWithExistingIosLocator() {
        assertThat(adapter.findLocatorFields(MethodRef.of(LOGIN_PAGE, "loginAs", "String", "String"))).containsExactly(
                new LocatorField(new FieldRef(LOGIN_PAGE, "passwordField"), "id = \"com.bank:id/password\"",
                        Optional.of("accessibility = \"password\"")),
                new LocatorField(new FieldRef(LOGIN_PAGE, "loginButton"),
                        "xpath = \"//android.widget.Button[@text='LOGIN']\"", Optional.empty()));
    }

    @Test
    void thisQualifiedAndPlainUsesOfAFieldAreOneLocator() {
        assertThat(adapter.findLocatorFields(MethodRef.of(LOGIN_PAGE, "enterEmail", "String", "boolean")))
                .extracting(field -> field.field().fieldName())
                .containsExactly("emailField");
    }

    @Test
    void parameterShadowingAFieldIsNotALocatorUse() {
        assertThat(adapter.findLocatorFields(MethodRef.of(LOGIN_PAGE, "typeIntoEmail", "String"))).isEmpty();
    }

    @Test
    void containerAnnotationsAreReportedWhole() {
        assertThat(adapter.findLocatorFields(MethodRef.of(CARDS_PAGE, "firstCardTitle")))
                .singleElement()
                .satisfies(field -> assertThat(field.androidLocator()).startsWith("@AndroidFindBys(")
                        .contains("com.bank:id/cards_list"));
        assertThat(adapter.findLocatorFields(MethodRef.of(CARDS_PAGE, "balance")))
                .singleElement()
                .satisfies(field -> assertThat(field.androidLocator()).startsWith("@AndroidFindAll("));
    }

    @Test
    void detectsUiScrollableAndInlineLocator() {
        assertThat(markerTypes(MethodRef.of(CARDS_PAGE, "scrollToCard", "String")))
                .containsExactlyInAnyOrder(ComplexityMarkerType.UI_SCROLLABLE, ComplexityMarkerType.INLINE_LOCATOR);
    }

    @Test
    void detectsHardwareBackAndAndroidDriverCast() {
        assertThat(markerTypes(MethodRef.of(CARDS_PAGE, "backToHome")))
                .containsExactlyInAnyOrder(ComplexityMarkerType.ANDROID_KEY_BACK, ComplexityMarkerType.ANDROID_ONLY_API);
    }

    @Test
    void detectsKeyboardNotificationsShellAndPlatformFeatures() {
        assertThat(markerTypes(MethodRef.of(PAYMENTS_PAGE, "enterAmount", "String")))
                .containsExactlyInAnyOrder(ComplexityMarkerType.HIDE_KEYBOARD, ComplexityMarkerType.ANDROID_ONLY_API);
        assertThat(markerTypes(MethodRef.of(PAYMENTS_PAGE, "checkPushNotification")))
                .containsExactlyInAnyOrder(ComplexityMarkerType.OPEN_NOTIFICATIONS, ComplexityMarkerType.ANDROID_ONLY_API);
        assertThat(markerTypes(MethodRef.of(PAYMENTS_PAGE, "payWithGooglePay")))
                .containsExactlyInAnyOrder(ComplexityMarkerType.INLINE_LOCATOR,
                        ComplexityMarkerType.PLATFORM_SPECIFIC_FEATURE);
    }

    @Test
    void markersCarryLocationAndASourceExcerpt() {
        assertThat(adapter.detectComplexityMarkers(MethodRef.of(CARDS_PAGE, "backToHome")))
                .filteredOn(marker -> marker.type() == ComplexityMarkerType.ANDROID_KEY_BACK)
                .singleElement()
                .satisfies(marker -> {
                    assertThat(marker.location()).isEqualTo(MethodRef.of(CARDS_PAGE, "backToHome"));
                    assertThat(marker.detail()).isEqualTo("AndroidKey.BACK");
                });
    }

    @Test
    void portablePageObjectMethodHasNoMarkers() {
        assertThat(adapter.detectComplexityMarkers(MethodRef.of(LOGIN_PAGE, "loginAs", "String", "String"))).isEmpty();
    }

    @Test
    void readsGroupsFromLiteralsConstantsAndClassLevelTest() {
        assertThat(adapter.findTestGroups(test("payWithGooglePay"))).containsExactly("smoke");
        assertThat(adapter.findTestGroups(test("backFromCards"))).containsExactly("regression");
        assertThat(adapter.findTestGroups(test("openCardDetails"))).isEmpty();
        assertThat(adapter.findTestGroups(new TestMethodRef(CLASS_LEVEL_TEST, "explicitTest")))
                .containsExactlyInAnyOrder("nightly", "slow");
        assertThat(adapter.findTestGroups(new TestMethodRef(CLASS_LEVEL_TEST, "implicitTestOne")))
                .containsExactly("nightly");
    }

    @Test
    void methodSourceKeepsOriginalFormatting() {
        assertThat(adapter.methodSource(MethodRef.of(LOGIN_PAGE, "enterEmail", "String"))).contains("""
                public LoginPage enterEmail(String email) {
                        emailField.clear();
                        emailField.sendKeys(email);
                        return this;
                    }""");
    }

    @Test
    void qualifiedParameterTypesMatchSimpleOnes() {
        assertThat(adapter.methodSource(MethodRef.of(LOGIN_PAGE, "enterEmail", "java.lang.String"))).isPresent();
    }

    private List<ComplexityMarkerType> markerTypes(MethodRef method) {
        return adapter.detectComplexityMarkers(method).stream().map(ComplexityMarker::type).distinct().toList();
    }

    private static TestMethodRef test(String method) {
        return new TestMethodRef(CARDS_TEST, method);
    }
}
