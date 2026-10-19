package alex.valker91.tm.codegen;

import alex.valker91.tm.config.MigrationConfig;
import alex.valker91.tm.model.IosLocator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PageObjectUpdater {

    public static void injectLocator(String pageClassName, String elementIdentifier, IosLocator locator) {
        String fileName = pageClassName.endsWith(".java") ? pageClassName : pageClassName + ".java";
        Path path = findFilePath(MigrationConfig.PAGES_DIR, fileName);

        if (path == null) {
            System.out.println("  [Skip] Файл " + fileName + " не найден.");
            return;
        }

        try {
            String content = Files.readString(path);

            // 1. Добавляем импорт iOSXCUITFindBy, если его нет
            if (!content.contains("import io.appium.java_client.pagefactory.iOSXCUITFindBy;")) {
                content = content.replaceFirst(
                        "(import io\\.appium\\.java_client\\.pagefactory\\.AndroidFindBy;)",
                        "$1\nimport io.appium.java_client.pagefactory.iOSXCUITFindBy;"
                );
            }

            String attribute = locator.toAnnotationAttribute();
            String iosAnnotation = String.format("@iOSXCUITFindBy(%s = \"%s\")\n    ", attribute, locator.value());

            // 2. Если такая аннотация уже есть — пропускаем
            if (content.contains("@iOSXCUITFindBy(" + attribute + " = \"" + locator.value() + "\")")) {
                return;
            }

            boolean updated = false;

            // 3. Ищем поле по базовому имени (enterEmail -> emailField)
            String baseName = extractBaseName(elementIdentifier);
            String fieldRegex = "(?i)private\\s+WebElement\\s+([a-zA-Z0-9_]*" + baseName + "[a-zA-Z0-9_]*);";
            Matcher fieldMatcher = Pattern.compile(fieldRegex).matcher(content);

            if (fieldMatcher.find()) {
                String fieldName = fieldMatcher.group(1);
                // Вставляем аннотацию аккуратно перед @AndroidFindBy нужного поля
                String targetRegex = "(@AndroidFindBy\\([^\\)]+\\)\\s+private\\s+WebElement\\s+" + Pattern.quote(fieldName) + ";)";
                if (content.matches("(?s).*" + targetRegex + ".*")) {
                    content = content.replaceFirst(targetRegex, iosAnnotation + "$1");
                    updated = true;
                }
            }

            // Fallback: ищем аннотацию @AndroidFindBy с совпадающим селектором
            if (!updated) {
                String byValueRegex = "(@AndroidFindBy\\([^\\n]*\"" + Pattern.quote(locator.value()) + "\"[^\\n]*\\))";
                if (Pattern.compile(byValueRegex).matcher(content).find()) {
                    content = content.replaceFirst(byValueRegex, iosAnnotation + "$1");
                    updated = true;
                }
            }

            if (updated) {
                Files.writeString(path, content);
                System.out.println("  [Code Updated] " + fileName + " -> @" + iosAnnotation.trim());
            }

        } catch (IOException e) {
            System.err.println("Ошибка при обновлении Page Object: " + e.getMessage());
        }
    }

    private static String extractBaseName(String identifier) {
        if (identifier == null) return "";
        return identifier.replaceAll("^(enter|click|is|get|open)", "")
                .replaceAll("(Displayed|ErrorDisplayed)$", "")
                .toLowerCase();
    }

    public static Path findFilePath(String baseDir, String fileName) {
        try (var stream = Files.walk(Path.of(baseDir))) {
            return stream.filter(p -> p.getFileName().toString().equals(fileName)).findFirst().orElse(null);
        } catch (Exception e) {
            return null;
        }
    }
}