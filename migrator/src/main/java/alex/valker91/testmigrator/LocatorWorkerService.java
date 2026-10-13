package alex.valker91.testmigrator;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

import java.util.List;

public interface LocatorWorkerService {
    @SystemMessage("Ты QA Automation Engineer. Подбираешь точные аннотации @iOSXCUITFindBy по дампу экрана iOS.")
    @UserMessage("""
        Элементы для маппинга:
        {{elements}}

        Сжатые интерактивные элементы экрана iOS:
        {{iosElements}}

        Сгенерируй подходящие аннотации @iOSXCUITFindBy. Приоритет: accessibility, predicate, classChain.
        """)
    Models.LocatorMapping mapLocators(
            @V("elements") List<Models.ElementNeedLocator> elements,
            @V("iosElements") String iosElements
    );
}
