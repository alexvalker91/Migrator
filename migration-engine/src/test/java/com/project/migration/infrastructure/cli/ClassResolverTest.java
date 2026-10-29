package com.project.migration.infrastructure.cli;

import com.project.migration.testsupport.BankFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClassResolverTest {

    @TempDir
    Path project;

    private ClassResolver resolver;

    @BeforeEach
    void setUp() throws IOException {
        BankFixture.copyTo(project);
        Path other = project.resolve("app/src/test/java/com/bank/other/ClassLevelTest.java");
        Files.createDirectories(other.getParent());
        Files.writeString(other, "package com.bank.other;\npublic class ClassLevelTest { @org.testng.annotations.Test public void t() { } }\n");
        resolver = new ClassResolver(BankFixture.analyzer(project));
    }

    @Test
    void resolvesSimpleNamesFileNamesAndQualifiedNames() {
        assertThat(resolver.resolve("CardsTest")).isEqualTo("com.bank.tests.CardsTest");
        assertThat(resolver.resolve("CardsTest.java")).isEqualTo("com.bank.tests.CardsTest");
        assertThat(resolver.resolve("com.bank.other.ClassLevelTest")).isEqualTo("com.bank.other.ClassLevelTest");
    }

    @Test
    void refusesAmbiguousUnknownAndNonTestClasses() {
        assertThatThrownBy(() -> resolver.resolve("ClassLevelTest")).isInstanceOf(UsageException.class)
                .hasMessageContaining("ambiguous").hasMessageContaining("com.bank.other.ClassLevelTest")
                .hasMessageContaining("com.bank.tests.ClassLevelTest");
        assertThatThrownBy(() -> resolver.resolve("NoSuchTest")).hasMessageContaining("no test class named 'NoSuchTest'");
        assertThatThrownBy(() -> resolver.resolve("com.bank.pages.HomePage")).hasMessageContaining("is not a test class");
    }
}
