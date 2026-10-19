package alex.valker91.tm.ai;

import alex.valker91.tm.model.IosLocator;
import alex.valker91.tm.model.TestStep;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

public interface LocatorResolverAiService {

    @SystemMessage("""
        Ты ведущий эксперт по iOS XCUITest и Appium.
        Твоя задача — найти наиболее надежный и стабильный локатор для платформы iOS,
        соответствующий аналогичному элементу на Android.
        
        Приоритет выбора стратегии:
        1. accessibility id (по атрибутам name/label)
        2. -ios class chain
        3. -ios predicate string
        4. xpath (только как крайняя мера)
        """)
    @UserMessage("""
        Целевое действие: {{step.actionType}}
        Описание элемента: {{step.targetElementDescription}}
        Android локатор: {{step.androidStrategy}} = "{{step.androidSelector}}"
        
        XML Android (текущий экран):
        {{androidXml}}
        
        XML iOS (текущий экран):
        {{iosXml}}
        """)
    IosLocator resolveLocator(
            @V("step") TestStep step,
            @V("androidXml") String androidXml,
            @V("iosXml") String iosXml
    );
}
