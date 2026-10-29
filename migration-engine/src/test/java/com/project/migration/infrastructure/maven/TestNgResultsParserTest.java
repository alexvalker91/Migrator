package com.project.migration.infrastructure.maven;

import com.project.migration.domain.model.run.TestOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestNgResultsParserTest {

    static final String RESULTS = """
            <?xml version="1.0" encoding="UTF-8"?>
            <testng-results skipped="1" failed="2" total="6" passed="3">
              <suite name="Surefire suite">
                <test name="Surefire test">
                  <class name="com.bank.tests.CardsTest">
                    <test-method status="PASS" name="restartApp" is-config="true"/>
                    <test-method status="PASS" name="openCardDetails" signature="openCardDetails(java.lang.String)"/>
                    <test-method status="PASS" name="openCardDetails" signature="openCardDetails(java.lang.String)"/>
                    <test-method status="PASS" name="loginWithOverload"/>
                    <test-method status="FAIL" name="backFromCards">
                      <exception class="java.lang.AssertionError">
                        <message><![CDATA[expected [Visa 4242] but found [Visa 4242 ]]]></message>
                        <full-stacktrace><![CDATA[java.lang.AssertionError: expected
                        at com.bank.tests.CardsTest.backFromCards(CardsTest.java:41)]]></full-stacktrace>
                      </exception>
                    </test-method>
                    <test-method status="PASS" name="mixed"/>
                    <test-method status="FAIL" name="mixed">
                      <exception class="org.openqa.selenium.NoSuchElementException">
                        <full-stacktrace><![CDATA[NoSuchElementException at line 9]]></full-stacktrace>
                      </exception>
                    </test-method>
                    <test-method status="SKIP" name="payWithGooglePay"/>
                  </class>
                  <class name="com.bank.tests.OtherTest">
                    <test-method status="FAIL" name="setup" is-config="true">
                      <exception class="java.lang.IllegalStateException">
                        <message><![CDATA[session was not created]]></message>
                      </exception>
                    </test-method>
                    <test-method status="SKIP" name="notRun"/>
                  </class>
                </test>
              </suite>
            </testng-results>
            """;

    @TempDir
    Path dir;

    @Test
    void foldsInvocationsAndMapsStatuses() throws Exception {
        List<TestNgResultsParser.MethodResult> results = TestNgResultsParser.parse(write(RESULTS));

        assertThat(results).extracting(r -> r.methodName() + ":" + r.outcome()).containsExactly(
                "openCardDetails:PASSED", "loginWithOverload:PASSED", "backFromCards:FAILED", "mixed:FAILED",
                "payWithGooglePay:SKIPPED", "notRun:SKIPPED");
        TestNgResultsParser.MethodResult failed = byName(results, "backFromCards");
        assertThat(failed.failure().orElseThrow().message()).isEqualTo("expected [Visa 4242] but found [Visa 4242 ]");
        assertThat(failed.failure().orElseThrow().stackTrace()).contains("CardsTest.java:41");
        assertThat(results.stream().filter(r -> r.methodName().equals("openCardDetails"))).hasSize(1);
    }

    @Test
    void aFailureWithoutMessageFallsBackToTheExceptionClass() throws Exception {
        TestNgResultsParser.MethodResult mixed = byName(TestNgResultsParser.parse(write(RESULTS)), "mixed");

        assertThat(mixed.outcome()).isEqualTo(TestOutcome.FAILED);
        assertThat(mixed.failure().orElseThrow().message()).isEqualTo("org.openqa.selenium.NoSuchElementException");
    }

    @Test
    void aFailedConfigurationMethodExplainsTheSkippedTestsOfItsClass() throws Exception {
        TestNgResultsParser.MethodResult skipped = byName(TestNgResultsParser.parse(write(RESULTS)), "notRun");

        assertThat(skipped.outcome()).isEqualTo(TestOutcome.SKIPPED);
        assertThat(skipped.failure().orElseThrow().message()).isEqualTo("session was not created");
        assertThat(TestNgResultsParser.configurationFailure(write(RESULTS), "com.bank.tests.OtherTest")).isPresent();
        assertThat(TestNgResultsParser.configurationFailure(write(RESULTS), "com.bank.tests.CardsTest")).isEmpty();
    }

    private Path write(String xml) throws IOException {
        return Files.writeString(dir.resolve("testng-results.xml"), xml);
    }

    private static TestNgResultsParser.MethodResult byName(List<TestNgResultsParser.MethodResult> results, String name) {
        return results.stream().filter(r -> r.methodName().equals(name)).findFirst().orElseThrow();
    }
}
