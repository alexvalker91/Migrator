package com.project.migration.infrastructure.maven;

import com.project.migration.domain.model.Platform;
import com.project.migration.domain.model.code.AddTestGroups;
import com.project.migration.domain.model.config.MavenConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Command lines of the Maven runs. {@code -Dtest=...} bypasses the suite XML, so the group filters the suites
 * apply are passed explicitly for class runs (single-method runs want exactly that method).
 */
final class MavenCommandBuilder {

    private final MavenConfig config;
    private final Path projectRoot;

    MavenCommandBuilder(MavenConfig config, Path projectRoot) {
        this.config = config;
        this.projectRoot = projectRoot;
    }

    List<String> compile() {
        List<String> command = base();
        command.add("test-compile");
        command.addAll(config.extraArgs());
        return command;
    }

    List<String> testMethod(String classFqcn, String methodName, Platform platform) {
        return test(classFqcn + "#" + methodName, platform, "");
    }

    List<String> testClass(String classFqcn, Platform platform) {
        return test(classFqcn, platform, excludedGroups(platform));
    }

    /** What a platform must not run: the other platform's own tests, and on iOS the isolated ones. */
    static String excludedGroups(Platform platform) {
        return platform == Platform.IOS
                ? AddTestGroups.ANDROID_ONLY + "," + AddTestGroups.IOS_NEEDS_FIX
                : AddTestGroups.IOS_ONLY;
    }

    private List<String> test(String selector, Platform platform, String excludedGroups) {
        List<String> command = base();
        command.add("test");
        command.addAll(config.extraArgs());
        config.testModule().ifPresent(module -> {
            command.add("-pl");
            command.add(module);
        });
        command.add("-Dtest=" + selector);
        command.add("-Dplatform=" + platform.runnerValue());
        command.add("-Dsurefire.failIfNoSpecifiedTests=false");
        command.add("-DfailIfNoTests=false");
        if (!excludedGroups.isEmpty()) {
            command.add("-DexcludedGroups=" + excludedGroups);
        }
        return command;
    }

    private List<String> base() {
        List<String> command = new ArrayList<>();
        command.add(executable());
        command.add("-B");
        return command;
    }

    private String executable() {
        if (config.executable().isPresent()) {
            return config.executable().get();
        }
        Path wrapper = projectRoot.resolve("mvnw");
        return Files.isExecutable(wrapper) ? wrapper.toString() : "mvn";
    }
}
