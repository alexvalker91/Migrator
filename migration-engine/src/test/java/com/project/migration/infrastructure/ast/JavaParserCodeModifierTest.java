package com.project.migration.infrastructure.ast;

import com.project.migration.domain.exception.CodeModificationException;
import com.project.migration.domain.model.code.AddImport;
import com.project.migration.domain.model.code.AddIosLocator;
import com.project.migration.domain.model.code.AddTestGroups;
import com.project.migration.domain.model.code.CodeChange;
import com.project.migration.domain.model.code.FieldRef;
import com.project.migration.domain.model.code.InsertCallBefore;
import com.project.migration.domain.model.code.IosLocatorStrategy;
import com.project.migration.domain.model.code.MethodRef;
import com.project.migration.domain.model.code.ModificationResult;
import com.project.migration.domain.model.code.ReplaceMethodBody;
import com.project.migration.domain.model.code.TestMethodRef;
import com.project.migration.domain.port.CodeModifierPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.project.migration.infrastructure.ast.BankProjectFixture.CARDS_PAGE;
import static com.project.migration.infrastructure.ast.BankProjectFixture.CARDS_TEST;
import static com.project.migration.infrastructure.ast.BankProjectFixture.GOOGLE_PAY_PAGE;
import static com.project.migration.infrastructure.ast.BankProjectFixture.HOME_PAGE;
import static com.project.migration.infrastructure.ast.BankProjectFixture.LOGIN_PAGE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JavaParserCodeModifierTest {

    @TempDir
    Path projectRoot;

    private JavaParserAstAdapter analyzer;
    private CodeModifierPort modifier;

    @BeforeEach
    void setUp() {
        BankProjectFixture.copyTo(projectRoot);
        analyzer = BankProjectFixture.adapter(projectRoot);
        modifier = analyzer.modifier();
    }

    @Test
    void addsIosLocatorAboveAndroidLocatorAndImportsIt() {
        Path homePage = file(HOME_PAGE, false);
        String before = BankProjectFixture.read(homePage);

        ModificationResult result = modifier.apply(List.of(
                new AddIosLocator(new FieldRef(HOME_PAGE, "cardsTile"), IosLocatorStrategy.ACCESSIBILITY_ID, "cards-tile")));

        String after = BankProjectFixture.read(homePage);
        assertThat(result.changedFiles()).containsExactly(homePage.toAbsolutePath().normalize());
        assertThat(result.appliedChanges()).isEqualTo(1);
        assertThat(after).contains("""
                    @iOSXCUITFindBy(accessibility = "cards-tile")
                    @AndroidFindBy(accessibility = "cards-tile")
                    private WebElement cardsTile;
                """);
        assertThat(after).contains("import io.appium.java_client.pagefactory.iOSXCUITFindBy;");
        assertThat(withoutLines(after, "@iOSXCUITFindBy", "import io.appium.java_client.pagefactory.iOSXCUITFindBy;"))
                .isEqualTo(before);
    }

    @Test
    void replacesExistingIosLocatorAndPreservesComments() {
        modifier.apply(List.of(new AddIosLocator(new FieldRef(LOGIN_PAGE, "passwordField"),
                IosLocatorStrategy.NS_PREDICATE, "name == \"password\" AND visible == 1")));

        String after = BankProjectFixture.read(file(LOGIN_PAGE, false));
        assertThat(after).contains("""
                    // password is never logged
                    @iOSXCUITFindBy(iOSNsPredicate = "name == \\"password\\" AND visible == 1")
                    @AndroidFindBy(id = "com.bank:id/password")
                    private WebElement passwordField;
                """);
        assertThat(after).doesNotContain("@iOSXCUITFindBy(accessibility = \"password\")");
        assertThat(after).containsOnlyOnce("import io.appium.java_client.pagefactory.iOSXCUITFindBy;");
    }

    @Test
    void analyzerSeesModifiedSources() {
        modifier.apply(List.of(
                new AddIosLocator(new FieldRef(HOME_PAGE, "cardsTile"), IosLocatorStrategy.ACCESSIBILITY_ID, "cards")));

        assertThat(analyzer.findLocatorFields(MethodRef.of(HOME_PAGE, "openCards")))
                .singleElement()
                .satisfies(field -> assertThat(field.existingIosLocator()).contains("accessibility = \"cards\""));
    }

    @Test
    void repeatedChangesAreNoOps() {
        List<CodeChange> changes = List.of(
                new AddIosLocator(new FieldRef(HOME_PAGE, "cardsTile"), IosLocatorStrategy.ACCESSIBILITY_ID, "cards-tile"),
                new InsertCallBefore(MethodRef.of(CARDS_PAGE, "blockCard"), "blockCardButton.click()",
                        "waitForLoadingToDisappear()"),
                new AddTestGroups(new TestMethodRef(CARDS_TEST, "payWithGooglePay"), Set.of(AddTestGroups.ANDROID_ONLY)),
                new AddImport(HOME_PAGE, "java.time.Duration", false));
        modifier.apply(changes);

        ModificationResult second = modifier.apply(changes);

        assertThat(second.hasChanges()).isFalse();
        assertThat(second.appliedChanges()).isZero();
    }

    @Test
    void insertsCallBeforeAnchorStatement() {
        modifier.apply(List.of(new InsertCallBefore(MethodRef.of(CARDS_PAGE, "blockCard"),
                "blockCardButton.click();", "scrollToElementByText(\"Block card\");")));

        assertThat(BankProjectFixture.read(file(CARDS_PAGE, false))).contains("""
                    public void blockCard() {
                        scrollToElementByText("Block card");
                        blockCardButton.click();
                    }
                """);
    }

    @Test
    void insertsBeforeNestedStatementInsideIfBlock() {
        modifier.apply(List.of(new InsertCallBefore(MethodRef.of(LOGIN_PAGE, "enterEmail", "String", "boolean"),
                "this.emailField.clear()", "safeHideKeyboard()")));

        assertThat(BankProjectFixture.read(file(LOGIN_PAGE, false))).contains("""
                        if (clearFirst) {
                            safeHideKeyboard();
                            this.emailField.clear();
                        }
                """);
    }

    @Test
    void replacesMethodBody() {
        modifier.apply(List.of(new ReplaceMethodBody(MethodRef.of(GOOGLE_PAY_PAGE, "confirm"),
                "{ waitForLoadingToDisappear(); payButton.click(); }")));

        assertThat(BankProjectFixture.read(file(GOOGLE_PAY_PAGE, false))).contains("""
                    public void confirm() {
                        waitForLoadingToDisappear();
                        payButton.click();
                    }
                }
                """);
        assertThat(analyzer.methodSource(MethodRef.of(GOOGLE_PAY_PAGE, "confirm")).orElseThrow())
                .contains("waitForLoadingToDisappear()");
    }

    @Test
    void mergesTestGroupsIntoAllAnnotationForms() {
        modifier.apply(List.of(
                new AddTestGroups(new TestMethodRef(CARDS_TEST, "payWithGooglePay"), Set.of(AddTestGroups.ANDROID_ONLY)),
                new AddTestGroups(new TestMethodRef(CARDS_TEST, "loginWithOverload"), Set.of(AddTestGroups.IOS_NEEDS_FIX)),
                new AddTestGroups(new TestMethodRef(CARDS_TEST, "backFromCards"), Set.of(AddTestGroups.IOS_ONLY)),
                new AddTestGroups(new TestMethodRef(CARDS_TEST, "openCardDetails"), Set.of("b-group", "a-group"))));

        String after = BankProjectFixture.read(file(CARDS_TEST, true));
        assertThat(after)
                .contains("@Test(groups = {\"smoke\", \"android-only\"})")
                .contains("    @Test(groups = {\"ios-needs-fix\"})\n    public void loginWithOverload()")
                .contains("@Test(groups = {Groups.REGRESSION, \"ios-only\"})")
                .contains("@Test(description = \"Card details are shown\", dataProvider = \"cards\", "
                        + "groups = {\"a-group\", \"b-group\"})");
        assertThat(analyzer.findTestGroups(new TestMethodRef(CARDS_TEST, "backFromCards")))
                .containsExactlyInAnyOrder("regression", "ios-only");
    }

    @Test
    void batchIsAllOrNothing() {
        Path homePage = file(HOME_PAGE, false);
        Path googlePay = file(GOOGLE_PAY_PAGE, false);
        String homeBefore = BankProjectFixture.read(homePage);
        String googlePayBefore = BankProjectFixture.read(googlePay);

        assertThatThrownBy(() -> modifier.apply(List.of(
                new AddIosLocator(new FieldRef(HOME_PAGE, "cardsTile"), IosLocatorStrategy.ACCESSIBILITY_ID, "x"),
                new ReplaceMethodBody(MethodRef.of(GOOGLE_PAY_PAGE, "confirm"), "{ payButton.click( }"))))
                .isInstanceOf(CodeModificationException.class)
                .hasMessageContaining("does not parse");

        assertThat(BankProjectFixture.read(homePage)).isEqualTo(homeBefore);
        assertThat(BankProjectFixture.read(googlePay)).isEqualTo(googlePayBefore);
    }

    @Test
    void rejectsInvalidTargets() {
        assertThatThrownBy(() -> modifier.apply(List.of(new AddIosLocator(
                new FieldRef("com.bank.Missing", "x"), IosLocatorStrategy.ACCESSIBILITY_ID, "x"))))
                .isInstanceOf(CodeModificationException.class).hasMessageContaining("not found");
        assertThatThrownBy(() -> modifier.apply(List.of(new AddIosLocator(
                new FieldRef(LOGIN_PAGE, "unusedNote"), IosLocatorStrategy.ACCESSIBILITY_ID, "x"))))
                .isInstanceOf(CodeModificationException.class).hasMessageContaining("no @AndroidFindBy");
        assertThatThrownBy(() -> modifier.apply(List.of(new AddIosLocator(
                new FieldRef(CARDS_PAGE, "blockCardButton"), IosLocatorStrategy.ACCESSIBILITY_ID, "x"))))
                .isInstanceOf(CodeModificationException.class).hasMessageContaining("several variables");
        assertThatThrownBy(() -> modifier.apply(List.of(new InsertCallBefore(
                MethodRef.of(CARDS_PAGE, "blockCard"), "missing()", "x()"))))
                .isInstanceOf(CodeModificationException.class).hasMessageContaining("not found");
        assertThatThrownBy(() -> modifier.apply(List.of(new AddTestGroups(
                new TestMethodRef(CARDS_TEST, "login"), Set.of("x")))))
                .isInstanceOf(CodeModificationException.class).hasMessageContaining("not annotated");
    }

    @Test
    void addImportSkipsCoveredImports() {
        ModificationResult result = modifier.apply(List.of(
                new AddImport(HOME_PAGE, "java.lang.String", false),
                new AddImport(HOME_PAGE, "com.bank.pages.CardsPage", false),
                new AddImport(HOME_PAGE, "io.appium.java_client.AppiumDriver", false),
                new AddImport(HOME_PAGE, "org.testng.Assert.assertTrue", true)));

        assertThat(result.appliedChanges()).isEqualTo(1);
        assertThat(BankProjectFixture.read(file(HOME_PAGE, false)))
                .contains("import static org.testng.Assert.assertTrue;");
    }

    @Test
    void emptyBatchChangesNothing() {
        assertThat(modifier.apply(List.of())).isEqualTo(new ModificationResult(List.of(), 0));
        assertThat(analyzer.sourceFileOf(LOGIN_PAGE)).isEqualTo(Optional.of(file(LOGIN_PAGE, false)));
    }

    private Path file(String fqcn, boolean test) {
        return BankProjectFixture.sourceOf(projectRoot, fqcn, test).toAbsolutePath().normalize();
    }

    private static String withoutLines(String source, String... fragments) {
        StringBuilder kept = new StringBuilder();
        for (String line : source.split("\n", -1)) {
            boolean drop = false;
            for (String fragment : fragments) {
                drop |= line.contains(fragment);
            }
            if (!drop) {
                kept.append(line).append('\n');
            }
        }
        return kept.substring(0, kept.length() - 1);
    }
}
