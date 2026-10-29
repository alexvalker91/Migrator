package com.project.migration.domain.model.config;

import com.project.migration.domain.model.Checks;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * How the target project's Maven build is run.
 *
 * @param executable   Maven command; empty = {@code ./mvnw} of the project if it exists, else {@code mvn}
 * @param extraArgs    arguments added to every run (profile that enables device tests, ...)
 * @param testModule   module that holds the tests ({@code -pl}); empty = whole reactor
 * @param compileTimeout / testTimeout / classTimeout  limits after which the process is killed
 */
public record MavenConfig(
        Optional<String> executable,
        List<String> extraArgs,
        Optional<String> testModule,
        Duration compileTimeout,
        Duration testTimeout,
        Duration classTimeout) {

    public static final MavenConfig DEFAULT = new MavenConfig(Optional.empty(), List.of("-Pe2e"), Optional.empty(),
            Duration.ofMinutes(5), Duration.ofMinutes(15), Duration.ofMinutes(60));

    public MavenConfig {
        executable = executable == null ? Optional.empty() : executable.filter(e -> !e.isBlank());
        extraArgs = List.copyOf(Checks.notNull(extraArgs, "extraArgs"));
        testModule = testModule == null ? Optional.empty() : testModule.filter(m -> !m.isBlank());
        positive(compileTimeout, "compileTimeout");
        positive(testTimeout, "testTimeout");
        positive(classTimeout, "classTimeout");
    }

    private static void positive(Duration value, String name) {
        Checks.notNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
