package com.project.migration.infrastructure.cli;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CliArgumentsTest {

    static final Path CWD = Path.of("/work/project");

    @Test
    void defaultsAreTheCurrentDirectoryAndPush() {
        CliArguments arguments = CliArguments.parse(new String[]{"CardsTest"}, CWD);

        assertThat(arguments.classes()).containsExactly("CardsTest");
        assertThat(arguments.projectRoot()).isEqualTo(CWD);
        assertThat(arguments.config()).isEmpty();
        assertThat(arguments.push()).isTrue();
        assertThat(arguments.pushOnRegression()).isFalse();
        assertThat(arguments.showReasoning()).isFalse();
        assertThat(arguments.help()).isFalse();
    }

    @Test
    void parsesEveryOptionInBothValueForms() {
        CliArguments arguments = CliArguments.parse(new String[]{"A", "--project-root", "../other", "B.java", "--config=cfg.json",
                "--show-reasoning", "--no-push", "--push-on-regression", "com.bank.CTest"}, CWD);

        assertThat(arguments.classes()).containsExactly("A", "B.java", "com.bank.CTest");
        assertThat(arguments.projectRoot()).isEqualTo(Path.of("/work/other"));
        assertThat(arguments.config()).contains(Path.of("/work/project/cfg.json"));
        assertThat(arguments.showReasoning()).isTrue();
        assertThat(arguments.push()).isFalse();
        assertThat(arguments.pushOnRegression()).isTrue();
    }

    @Test
    void helpNeedsNoClass() {
        assertThat(CliArguments.parse(new String[]{"--help"}, CWD).help()).isTrue();
        assertThat(CliArguments.parse(new String[]{"-h"}, CWD).help()).isTrue();
    }

    @Test
    void rejectsWhatItDoesNotUnderstand() {
        assertThatThrownBy(() -> CliArguments.parse(new String[]{}, CWD)).isInstanceOf(UsageException.class).hasMessage("no test class given");
        assertThatThrownBy(() -> CliArguments.parse(new String[]{"A", "--bogus"}, CWD)).hasMessage("unknown option --bogus");
        assertThatThrownBy(() -> CliArguments.parse(new String[]{"A", "-x"}, CWD)).hasMessage("unknown option -x");
        assertThatThrownBy(() -> CliArguments.parse(new String[]{"A", "--config"}, CWD)).hasMessage("--config needs a value");
        assertThatThrownBy(() -> CliArguments.parse(new String[]{"A", "--project-root", "--no-push"}, CWD))
                .hasMessage("--project-root needs a value");
        assertThatThrownBy(() -> CliArguments.parse(new String[]{"A", "--config="}, CWD)).hasMessage("--config needs a value");
    }
}
