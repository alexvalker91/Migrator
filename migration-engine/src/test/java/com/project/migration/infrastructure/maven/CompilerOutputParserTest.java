package com.project.migration.infrastructure.maven;

import com.project.migration.domain.model.run.CompilationError;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CompilerOutputParserTest {

    @Test
    void extractsDistinctJavacErrorsWithFileLineAndMessage() {
        String output = """
                [INFO] Compiling 12 source files
                [ERROR] COMPILATION ERROR :
                [ERROR] /work/app/src/main/java/com/bank/pages/CardsPage.java:[44,9] cannot find symbol
                [ERROR] /work/app/src/main/java/com/bank/pages/HomePage.java:[7] ';' expected
                [INFO] 2 errors
                [ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.14.1:compile
                [ERROR] /work/app/src/main/java/com/bank/pages/CardsPage.java:[44,9] cannot find symbol
                """;

        List<CompilationError> errors = CompilerOutputParser.parse(output);

        assertThat(errors).containsExactly(
                new CompilationError(Path.of("/work/app/src/main/java/com/bank/pages/CardsPage.java"), 44, "cannot find symbol"),
                new CompilationError(Path.of("/work/app/src/main/java/com/bank/pages/HomePage.java"), 7, "';' expected"));
    }

    @Test
    void outputWithoutCompilerErrorsYieldsNone() {
        assertThat(CompilerOutputParser.parse("[INFO] BUILD SUCCESS\n[ERROR] Some other failure")).isEmpty();
    }
}
