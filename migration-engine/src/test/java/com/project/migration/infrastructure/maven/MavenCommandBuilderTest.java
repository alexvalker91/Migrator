package com.project.migration.infrastructure.maven;

import com.project.migration.domain.model.Platform;
import com.project.migration.domain.model.config.MavenConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class MavenCommandBuilderTest {

    @TempDir
    Path root;

    @Test
    void singleMethodRunSelectsTheMethodAndThePlatformWithoutGroupFilters() {
        List<String> command = new MavenCommandBuilder(config(Optional.of("mvn"), Optional.of("migrator")), root)
                .testMethod("com.bank.tests.CardsTest", "openCardDetails", Platform.IOS);

        assertThat(command).containsExactly("mvn", "-B", "test", "-Pe2e", "-pl", "migrator",
                "-Dtest=com.bank.tests.CardsTest#openCardDetails", "-Dplatform=iOS",
                "-Dsurefire.failIfNoSpecifiedTests=false", "-DfailIfNoTests=false");
    }

    @Test
    void classRunExcludesTheGroupsOfTheOtherPlatform() {
        MavenCommandBuilder builder = new MavenCommandBuilder(config(Optional.of("mvn"), Optional.empty()), root);

        assertThat(builder.testClass("a.T", Platform.IOS)).contains("-Dtest=a.T", "-Dplatform=iOS",
                "-DexcludedGroups=android-only,ios-needs-fix").doesNotContain("-pl");
        assertThat(builder.testClass("a.T", Platform.ANDROID)).contains("-Dplatform=Android", "-DexcludedGroups=ios-only");
    }

    @Test
    void compilesTheWholeProjectWithTheConfiguredArguments() {
        assertThat(new MavenCommandBuilder(config(Optional.of("mvn"), Optional.of("migrator")), root).compile())
                .containsExactly("mvn", "-B", "test-compile", "-Pe2e");
    }

    @Test
    void prefersTheProjectWrapperAndFallsBackToMvn() throws IOException {
        MavenCommandBuilder builder = new MavenCommandBuilder(config(Optional.empty(), Optional.empty()), root);
        assertThat(builder.compile().getFirst()).isEqualTo("mvn");

        Path wrapper = Files.writeString(root.resolve("mvnw"), "#!/bin/sh\n");
        Files.setPosixFilePermissions(wrapper, PosixFilePermissions.fromString("rwxr-xr-x"));
        assertThat(builder.compile().getFirst()).isEqualTo(wrapper.toString());
    }

    private static MavenConfig config(Optional<String> executable, Optional<String> module) {
        return new MavenConfig(executable, List.of("-Pe2e"), module, Duration.ofMinutes(5), Duration.ofMinutes(15),
                Duration.ofMinutes(60));
    }
}
