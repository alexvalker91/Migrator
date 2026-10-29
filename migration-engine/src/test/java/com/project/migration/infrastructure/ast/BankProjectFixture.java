package com.project.migration.infrastructure.ast;

import com.project.migration.domain.model.code.FrameworkContract;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Copies the synthetic banking project ({@code fixtures/bank-project}) into a temp directory, so tests can
 * modify it freely.
 */
final class BankProjectFixture {

    static final FrameworkContract FRAMEWORK = new FrameworkContract(
            "com.bank.framework.BasePage", "com.bank.framework.BaseTest", Set.of("GooglePay", "GoogleWallet"));

    static final String LOGIN_PAGE = "com.bank.pages.LoginPage";
    static final String HOME_PAGE = "com.bank.pages.HomePage";
    static final String CARDS_PAGE = "com.bank.pages.CardsPage";
    static final String PAYMENTS_PAGE = "com.bank.pages.PaymentsPage";
    static final String GOOGLE_PAY_PAGE = "com.bank.pages.GooglePayPage";
    static final String BOTTOM_NAV = "com.bank.pages.BottomNav";
    static final String CARDS_TEST = "com.bank.tests.CardsTest";
    static final String CLASS_LEVEL_TEST = "com.bank.tests.ClassLevelTest";

    private BankProjectFixture() {
    }

    static Path copyTo(Path target) {
        try {
            Path source = Path.of(BankProjectFixture.class.getResource("/fixtures/bank-project").toURI());
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

    static JavaParserAstAdapter adapter(Path projectRoot) {
        return new JavaParserAstAdapter(projectRoot, List.of(), FRAMEWORK);
    }

    static Path sourceOf(Path projectRoot, String fqcn, boolean test) {
        return projectRoot.resolve("app/src/" + (test ? "test" : "main") + "/java/" + fqcn.replace('.', '/') + ".java");
    }

    static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
