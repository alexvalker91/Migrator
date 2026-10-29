package com.project.migration.testsupport;

import com.project.migration.domain.model.code.FrameworkContract;
import com.project.migration.infrastructure.ast.JavaParserAstAdapter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The synthetic banking project ({@code fixtures/bank-project}) copied to a temp directory.
 */
public final class BankFixture {

    public static final FrameworkContract FRAMEWORK = new FrameworkContract(
            "com.bank.framework.BasePage", "com.bank.framework.BaseTest", Set.of("GooglePay", "GoogleWallet"));

    private BankFixture() {
    }

    public static Path copyTo(Path target) {
        try {
            Path source = Path.of(BankFixture.class.getResource("/fixtures/bank-project").toURI());
            try (Stream<Path> files = Files.walk(source)) {
                for (Path file : files.toList()) {
                    Path destination = target.resolve(source.relativize(file).toString());
                    if (Files.isDirectory(file)) {
                        Files.createDirectories(destination);
                    } else {
                        Files.copy(file, destination);
                    }
                }
            }
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    public static JavaParserAstAdapter analyzer(Path projectRoot) {
        return new JavaParserAstAdapter(projectRoot, List.of(), FRAMEWORK);
    }

    public static Path source(Path projectRoot, String fqcn, boolean test) {
        return projectRoot.resolve("app/src/" + (test ? "test" : "main") + "/java/" + fqcn.replace('.', '/') + ".java");
    }
}
