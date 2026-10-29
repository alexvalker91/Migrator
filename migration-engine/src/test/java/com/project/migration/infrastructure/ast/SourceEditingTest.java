package com.project.migration.infrastructure.ast;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.project.migration.domain.exception.CodeModificationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SourceEditingTest {

    @TempDir
    Path dir;

    @Test
    void editsKeepWindowsLineEndings() {
        String source = "class A {\r\n    @Old\r\n    @AndroidFindBy(id = \"x\")\r\n    WebElement e;\r\n}\r\n";
        CompilationUnit unit = Snippets.parser().parse(source).getResult().orElseThrow();
        FieldDeclaration field = unit.findFirst(FieldDeclaration.class).orElseThrow();
        SourceText text = new SourceText(source);

        int at = text.begin(field.getAnnotation(1));
        String edited = text.apply(List.of(
                text.removal(field.getAnnotation(0)),
                new SourceText.Edit(at, at, "@New" + text.lineSeparator() + text.indentationAt(at))));

        assertThat(edited).isEqualTo("class A {\r\n    @New\r\n    @AndroidFindBy(id = \"x\")\r\n    WebElement e;\r\n}\r\n");
    }

    @Test
    void overlappingEditsAreRejected() {
        SourceText text = new SourceText("abcdef");

        assertThatThrownBy(() -> text.apply(List.of(new SourceText.Edit(0, 3, "x"), new SourceText.Edit(2, 4, "y"))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void failedWriteRestoresAlreadyWrittenFiles() throws IOException {
        Path first = Files.writeString(dir.resolve("First.java"), "original");
        Path unwritable = Files.createDirectories(dir.resolve("Second.java")); // non-empty directory: cannot be replaced
        Files.writeString(unwritable.resolve("keep"), "x");
        Map<Path, String> contents = new LinkedHashMap<>();
        contents.put(first, "changed");
        contents.put(unwritable, "changed");
        Map<Path, String> originals = Map.of(first, "original", unwritable, "original");

        assertThatThrownBy(() -> new AtomicFileWriter().writeAll(contents, originals))
                .isInstanceOf(CodeModificationException.class)
                .hasMessageContaining("restored");
        assertThat(Files.readString(first)).isEqualTo("original");
        try (var files = Files.list(dir)) {
            assertThat(files.map(path -> path.getFileName().toString()))
                    .containsExactlyInAnyOrder("First.java", "Second.java");
        }
    }
}
