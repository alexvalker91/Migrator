package com.bank.tests;

import com.bank.framework.BaseTest;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

@Test(groups = "nightly")
public class ClassLevelTest extends BaseTest {

    public void implicitTestOne() {
    }

    @Test(groups = "slow")
    public void explicitTest() {
    }

    @AfterMethod
    public void cleanUp() {
    }

    public static void staticHelper() {
    }

    void packagePrivateHelper() {
    }
}
