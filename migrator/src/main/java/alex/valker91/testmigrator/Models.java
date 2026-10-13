package alex.valker91.testmigrator;

import java.util.List;
import java.util.Map;

public class Models {

    public record ElementNeedLocator(String fieldName, String androidLocator, String intent) {}

    public record MigrationPlan(
            List<ElementNeedLocator> elementsToMap,
            List<String> iosSpecificFixes
    ) {}

    public record LocatorMapping(
            // Map: имя переменной -> готовая строка аннотации, например "@iOSXCUITFindBy(accessibility = \"...\")"
            Map<String, String> mappedLocators
    ) {}
}
