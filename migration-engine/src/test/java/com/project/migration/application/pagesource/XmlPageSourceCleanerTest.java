package com.project.migration.application.pagesource;

import com.project.migration.domain.exception.McpException;
import com.project.migration.domain.model.Platform;
import com.project.migration.domain.model.mcp.RawPageSource;
import com.project.migration.domain.model.screen.CleaningResult;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class XmlPageSourceCleanerTest {

    private final XmlPageSourceCleaner cleaner = new XmlPageSourceCleaner();

    @Test
    void androidKeepsOnlyWhitelistedNonDefaultAttributesAndShortensStandardTags() {
        String cleaned = clean(Platform.ANDROID, """
                <hierarchy index="0" class="hierarchy" rotation="0" width="1080" height="2400">
                  <android.widget.TextView index="0" package="com.app" class="android.widget.TextView"
                      text="Balance" resource-id="com.app:id/title" content-desc="" checkable="false"
                      enabled="true" focused="false" scrollable="false" bounds="[0,0][100,50]" displayed="true"/>
                  <android.widget.Button index="1" class="android.widget.Button" text="Pay" resource-id=""
                      enabled="false" displayed="true"/>
                </hierarchy>
                """);

        assertThat(cleaned).isEqualTo("""
                <hierarchy platform="android" package="com.app">
                 <TextView resource-id="title" text="Balance"/>
                 <Button text="Pay" enabled="false"/>
                </hierarchy>
                """);
    }

    @Test
    void customAndroidViewsKeepFullyQualifiedClassForXpathLocators() {
        String cleaned = clean(Platform.ANDROID, """
                <hierarchy>
                  <com.bank.ui.AmountView class="com.bank.ui.AmountView" text="10 BYN" displayed="true"/>
                  <androidx.recyclerview.widget.RecyclerView resource-id="com.app:id/list" scrollable="true"
                      displayed="true"/>
                </hierarchy>
                """);

        assertThat(cleaned)
                .contains("<com.bank.ui.AmountView text=\"10 BYN\"/>")
                .contains("<RecyclerView resource-id=\"com.app:id/list\" scrollable=\"true\" "
                        + "scrollable_container=\"true\"/>");
    }

    @Test
    void iosKeepsWhitelistedAttributesAndStripsTypePrefix() {
        String cleaned = clean(Platform.IOS, """
                <AppiumAUT>
                  <XCUIElementTypeApplication type="XCUIElementTypeApplication" name="Bank" label="Bank"
                      enabled="true" visible="true" accessible="true" x="0" y="0" width="390" height="844" index="0">
                    <XCUIElementTypeButton type="XCUIElementTypeButton" name="login" label="Log in" enabled="true"
                        visible="true" x="10" y="10" width="100" height="40" index="0"/>
                  </XCUIElementTypeApplication>
                </AppiumAUT>
                """);

        assertThat(cleaned).isEqualTo("""
                <hierarchy platform="ios">
                 <Application name="Bank">
                  <Button name="login" label="Log in"/>
                 </Application>
                </hierarchy>
                """);
    }

    @Test
    void appResourceIdsAreShortenedAgainstRootPackageButForeignOnesAreKept() {
        String cleaned = clean(Platform.ANDROID, """
                <hierarchy>
                  <android.widget.FrameLayout package="com.bank" resource-id="android:id/content" displayed="true">
                    <android.widget.TextView package="com.bank" resource-id="com.bank:id/balance" displayed="true"/>
                  </android.widget.FrameLayout>
                </hierarchy>
                """);

        assertThat(cleaned).isEqualTo("""
                <hierarchy platform="android" package="com.bank">
                 <FrameLayout resource-id="android:id/content">
                  <TextView resource-id="balance"/>
                 </FrameLayout>
                </hierarchy>
                """);
    }

    @Test
    void iosLabelAndValueEqualToPreviousAttributeAreOmitted() {
        String cleaned = clean(Platform.IOS, """
                <AppiumAUT>
                  <XCUIElementTypeStaticText name="Coffee" label="Coffee" value="Coffee" visible="true"/>
                  <XCUIElementTypeTextField name="amount" label="Amount" value="Amount" visible="true"/>
                  <XCUIElementTypeTextField name="amount2" label="Amount" value="10" visible="true"/>
                </AppiumAUT>
                """);

        assertThat(cleaned).isEqualTo("""
                <hierarchy platform="ios">
                 <StaticText name="Coffee"/>
                 <TextField name="amount" label="Amount"/>
                 <TextField name="amount2" label="Amount" value="10"/>
                </hierarchy>
                """);
    }

    @Test
    void invisibleElementWithSemanticsIsKeptAsOffscreenForNativeScroll() {
        String cleaned = clean(Platform.IOS, """
                <AppiumAUT>
                  <XCUIElementTypeTable type="XCUIElementTypeTable" visible="true">
                    <XCUIElementTypeCell type="XCUIElementTypeCell" visible="false">
                      <XCUIElementTypeStaticText name="Save" label="Save" visible="false"/>
                    </XCUIElementTypeCell>
                  </XCUIElementTypeTable>
                </AppiumAUT>
                """);

        assertThat(cleaned).isEqualTo("""
                <hierarchy platform="ios">
                 <Table scrollable_container="true">
                  <Cell>
                   <StaticText name="Save" offscreen="true"/>
                  </Cell>
                 </Table>
                </hierarchy>
                """);
    }

    @Test
    void invisibleBranchWithoutSemanticsIsDroppedEntirely() {
        String cleaned = clean(Platform.IOS, """
                <AppiumAUT>
                  <XCUIElementTypeWindow visible="false">
                    <XCUIElementTypeOther visible="false"><XCUIElementTypeImage visible="false"/></XCUIElementTypeOther>
                  </XCUIElementTypeWindow>
                  <XCUIElementTypeButton name="ok" visible="true"/>
                </AppiumAUT>
                """);

        assertThat(cleaned).isEqualTo("""
                <hierarchy platform="ios">
                 <Button name="ok"/>
                </hierarchy>
                """);
    }

    @Test
    void androidDisplayedFalseIsTreatedAsInvisible() {
        String cleaned = clean(Platform.ANDROID, """
                <hierarchy>
                  <android.widget.ImageView displayed="false"/>
                  <android.widget.TextView text="Below the fold" displayed="false"/>
                </hierarchy>
                """);

        assertThat(cleaned).isEqualTo("""
                <hierarchy platform="android">
                 <TextView text="Below the fold" offscreen="true"/>
                </hierarchy>
                """);
    }

    @Test
    void attributeLessWrappersAreCollapsedAndChildrenLifted() {
        String cleaned = clean(Platform.ANDROID, """
                <hierarchy>
                  <android.widget.FrameLayout class="android.widget.FrameLayout" displayed="true">
                    <android.view.ViewGroup displayed="true">
                      <android.view.ViewGroup displayed="true">
                        <android.widget.TextView text="Hello" displayed="true"/>
                      </android.view.ViewGroup>
                    </android.view.ViewGroup>
                    <android.view.ViewGroup resource-id="com.app:id/card" displayed="true">
                      <android.widget.TextView text="Card" displayed="true"/>
                    </android.view.ViewGroup>
                  </android.widget.FrameLayout>
                </hierarchy>
                """);

        assertThat(cleaned).isEqualTo("""
                <hierarchy platform="android">
                 <TextView text="Hello"/>
                 <ViewGroup resource-id="com.app:id/card">
                  <TextView text="Card"/>
                 </ViewGroup>
                </hierarchy>
                """);
    }

    @Test
    void listRowsAreKeptAsGroupsEvenWithoutAttributes() {
        String cleaned = clean(Platform.ANDROID, """
                <hierarchy>
                  <androidx.recyclerview.widget.RecyclerView displayed="true">
                    <android.view.ViewGroup displayed="true">
                      <android.widget.LinearLayout displayed="true">
                        <android.widget.TextView text="Coffee" displayed="true"/>
                        <android.widget.TextView text="-5 BYN" displayed="true"/>
                      </android.widget.LinearLayout>
                    </android.view.ViewGroup>
                    <android.view.ViewGroup displayed="true">
                      <android.widget.TextView text="Taxi" displayed="true"/>
                    </android.view.ViewGroup>
                    <android.view.ViewGroup displayed="true"/>
                  </androidx.recyclerview.widget.RecyclerView>
                </hierarchy>
                """);

        assertThat(cleaned).isEqualTo("""
                <hierarchy platform="android">
                 <RecyclerView scrollable_container="true">
                  <ViewGroup>
                   <TextView text="Coffee"/>
                   <TextView text="-5 BYN"/>
                  </ViewGroup>
                  <ViewGroup>
                   <TextView text="Taxi"/>
                  </ViewGroup>
                  <ViewGroup/>
                 </RecyclerView>
                </hierarchy>
                """);
    }

    @Test
    void iosZonesAreMarked() {
        String cleaned = clean(Platform.IOS, """
                <AppiumAUT>
                  <XCUIElementTypeNavigationBar name="Cards" visible="true"/>
                  <XCUIElementTypeTabBar visible="true"><XCUIElementTypeButton name="Home" visible="true"/></XCUIElementTypeTabBar>
                  <XCUIElementTypeSheet visible="true"><XCUIElementTypeButton name="Close" visible="true"/></XCUIElementTypeSheet>
                  <XCUIElementTypeCollectionView visible="true"/>
                </AppiumAUT>
                """);

        assertThat(cleaned)
                .contains("<NavigationBar name=\"Cards\" zone=\"TOP_APP_BAR\"/>")
                .contains("<TabBar zone=\"BOTTOM_NAV_BAR\">")
                .contains("<Sheet zone=\"BOTTOM_SHEET\">")
                .contains("<CollectionView scrollable_container=\"true\"/>");
    }

    @Test
    void androidZonesAreMarkedAndKeepOtherwiseCollapsibleContainers() {
        String cleaned = clean(Platform.ANDROID, """
                <hierarchy>
                  <androidx.appcompat.widget.Toolbar displayed="true"><android.widget.TextView text="History" displayed="true"/></androidx.appcompat.widget.Toolbar>
                  <com.google.android.material.bottomnavigation.BottomNavigationView displayed="true"/>
                  <android.widget.FrameLayout resource-id="com.app:id/design_bottom_sheet" displayed="true"/>
                  <android.widget.ScrollView displayed="true"/>
                </hierarchy>
                """);

        assertThat(cleaned)
                .contains("<Toolbar zone=\"TOP_APP_BAR\">")
                .contains("<com.google.android.material.bottomnavigation.BottomNavigationView zone=\"BOTTOM_NAV_BAR\"/>")
                .contains("<FrameLayout resource-id=\"com.app:id/design_bottom_sheet\" zone=\"BOTTOM_SHEET\"/>")
                .contains("<ScrollView scrollable_container=\"true\"/>");
    }

    @Test
    void secretsAndPasswordFieldsAreMasked() {
        String android = clean(Platform.ANDROID, """
                <hierarchy>
                  <android.widget.TextView text="Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.sig" displayed="true"/>
                  <android.widget.EditText resource-id="com.app:id/password" text="hunter2" password="true" displayed="true"/>
                  <android.widget.TextView text="Visa 4111 1111 1111 1111" displayed="true"/>
                </hierarchy>
                """);
        String ios = clean(Platform.IOS, """
                <AppiumAUT>
                  <XCUIElementTypeSecureTextField name="pin_input" value="1234" visible="true"/>
                </AppiumAUT>
                """);

        assertThat(android)
                .contains("text=\"Authorization: Bearer ***MASKED***\"")
                .contains("<EditText resource-id=\"com.app:id/password\" text=\"***MASKED***\"/>")
                .contains("text=\"Visa **** **** **** 1111\"")
                .doesNotContain("hunter2", "eyJ", "4111 1111");
        assertThat(ios).contains("<SecureTextField name=\"pin_input\" value=\"***MASKED***\"/>");
    }

    @Test
    void attributeValuesAreEscaped() {
        String cleaned = clean(Platform.IOS, """
                <AppiumAUT><XCUIElementTypeStaticText label="A &amp; B &lt;&quot;x&quot;&gt;" visible="true"/></AppiumAUT>
                """);

        assertThat(cleaned).contains("label=\"A &amp; B &lt;&quot;x&quot;&gt;\"");
    }

    @Test
    void largeAndroidBankingScreenIsCompressedAtLeastFifteenTimes() {
        CleaningResult result = cleaner.clean(raw(Platform.ANDROID, fixture("android_operations_history_raw.xml")));

        System.out.printf("android fixture: %.1fx%n", result.compressionRatio());
        assertThat(result.compressionRatio()).isGreaterThanOrEqualTo(15.0);
        assertThat(result.savedTokens()).isEqualTo(result.rawTokensEstimate() - result.cleanedTokensEstimate());
        assertThat(result.cleanedXml())
                .contains("zone=\"TOP_APP_BAR\"", "zone=\"BOTTOM_NAV_BAR\"", "scrollable_container=\"true\"")
                .contains("<hierarchy platform=\"android\" package=\"com.bank.mobile\">")
                .contains("<com.bank.mobile.ui.AmountView resource-id=\"balance\"")
                .contains("Visa Gold **** **** **** 1111", "Token: Bearer ***MASKED***")
                .containsOnlyOnce("package=")
                .doesNotContain("bounds=", "index=", "displayed=", "class=");
    }

    @Test
    void largeIosBankingScreenIsCompressedAndMarksOffscreenCells() {
        CleaningResult result = cleaner.clean(raw(Platform.IOS, fixture("ios_operations_history_raw.xml")));

        System.out.printf("ios fixture: %.1fx%n", result.compressionRatio());
        assertThat(result.compressionRatio()).isGreaterThanOrEqualTo(5.0);
        assertThat(result.cleanedXml())
                .contains("offscreen=\"true\"", "zone=\"TOP_APP_BAR\"", "zone=\"BOTTOM_NAV_BAR\"")
                .contains("<Table name=\"operations_list\" scrollable_container=\"true\">")
                .contains("value=\"***MASKED***\"")
                .doesNotContain("XCUIElementType", "visible=", "x=\"", "<Other");
    }

    @Test
    void cleaningIsDeterministic() {
        RawPageSource source = raw(Platform.IOS, fixture("ios_operations_history_raw.xml"));

        assertThat(cleaner.clean(source)).isEqualTo(cleaner.clean(source));
    }

    @Test
    void malformedXmlIsAnMcpFailure() {
        assertThatThrownBy(() -> cleaner.clean(raw(Platform.ANDROID, "<hierarchy><broken")))
                .isInstanceOf(McpException.class)
                .hasMessageContaining("not well-formed");
    }

    @Test
    void doctypeIsRejectedToPreventXxe() {
        String xxe = """
                <?xml version="1.0"?>
                <!DOCTYPE hierarchy [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <hierarchy><android.widget.TextView text="&xxe;"/></hierarchy>
                """;

        assertThatThrownBy(() -> cleaner.clean(raw(Platform.ANDROID, xxe))).isInstanceOf(McpException.class);
    }

    private String clean(Platform platform, String xml) {
        return cleaner.clean(raw(platform, xml)).cleanedXml();
    }

    private static RawPageSource raw(Platform platform, String xml) {
        return new RawPageSource(platform, xml, Instant.parse("2026-10-29T10:00:00Z"));
    }

    private static String fixture(String name) {
        try (InputStream in = XmlPageSourceCleanerTest.class.getResourceAsStream("/pagesource/" + name)) {
            if (in == null) {
                throw new IllegalStateException("Missing fixture " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
